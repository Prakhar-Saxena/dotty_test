package io.getquill.derived

import io.getquill.quoter._
import scala.reflect.ClassTag
import scala.compiletime.{erasedValue, summonFrom, constValue}
import io.getquill.ast.{Tuple => AstTuple, Map => AMap, Query => AQuery, _}
import scala.compiletime.erasedValue
import io.getquill.ast.Visibility.{ Hidden, Visible }
import scala.deriving._
import scala.quoted._
import io.getquill.parser.Lifter
import io.getquill.quat.Quat
import io.getquill.ast.{Map => AMap, _}

/**
 * Based on valueComputation and materializeQueryMeta from the old Quill
 * This was around to flesh-out details of the outermost AST of a query based on the fields of the
 * object T in Query[T] that the AST represents. For an example say we have something like this:
 * {{{
 * import io.getquill.ast.{ Ident => Id, Property => Prop, _ }
 * case class Person(name: String, age: Int)
 * query[Person].map(p => p) // or just query[Person]
 * }}}
 * That would turn into an AST that looks like this:
 * {{{
 * Map(EntityQuery("Person"), Id("p"), Id("p"))
 * }}}
 * This query needs to be turned into `SELECT p.name, p.age from Person p`, the problem is, before
 * Quats, Quill did not actually know how to expand `Ident("p")` into `SelectValue(p.name), SelectValue(p.age)`
 * (see SqlQuery.scala) since there was no type information. Therefore...
 * {{{
 * // We needed to convert something that looks like this:
 * query[Person].map(p => p) // i.e. Map(EntityQuery("Person"), Id("p"), Id("p"))
 * 
 * // Into something that looks like this:
 * query[Person].map(p => p).map(p => (p.name, p.age)) 
 * // i.e. Map(Map(EntityQuery("Person"), Ident("p"), Ident("p")), Tuple(Prop(Id("p"),"name"), Prop(Id("p"),"age")))
 * }}}
 * This makes it easier to translate the above information into the finalized form
 * {{{
 * SELECT p.name, p.age FROM (SELECT p.* from Person p) AS p
 * }}}
 * (Note that redudant map would typically be flattened out since it is extraneous and the inner 
 * SELECT would no longer be present)
 *
 * Some special provisions were made for fields inside optional objects:
 * {{{
 * case class Address(street: String, zip: Int)
 * case class Person(name: String, address: Option[Address])
 * // This:
 * query[Person]
 * // Would become this:
 * query[Person].map(p => (p.name, p.address.map(_.street), p.address.map(_.zip)))
 * }}}
 * 
 * Now, since Quats were introduced into Quill since 3.6.0 (technically since 3.5.3), this step is not necessarily needed
 * for query expansion since `Ident("p")` is now understood to expand into it's corresponding SelectValue fields so for queries,
 * this stage could technically be elimiated. However, this logic is also useful for ActionMeta where we have
 * something like this:
 * {{{
 * case class Person(name: String, age: Int)
 * // This:
 * query[Person].insert(Person("Joe", 44))
 * // Needs to be converted into this:
 * query[Person].insert(_.name -> "Joe", _.age -> 44)
 * // Which is actually:
 * EntityQuery("Person").insert(
 *   Assignment(Id("x1"), Prop(Id("x1"), "name"), Const("Joe")), 
 *   Assignment(Id("x1"), Prop(Id("x1"), "name"), Const(44))
 * )
 * }}}
 * The fact that we know that Person expands into Prop(Id("p"),"name"), Prop(Id("p"),"age")) helps
 * us compute the necessary assignments in the `InsertMacro`.
 */
object ElaborateStructure {
  import io.getquill.dsl.GenericDecoder

  sealed trait TermType
  case object Leaf extends TermType
  case object Branch extends TermType

  case class Term(name: String, typeType: TermType, children: List[Term] = List(), optional: Boolean = false) {
    def withChildren(children: List[Term]) = this.copy(children = children)
    def toAst = Term.toAstTop(this)

    // Used by coproducts, merges all fields of a term with another if this is valid
    // Note that T is only needed for the error message. Maybe take it out once we store Types inside of Term
    def merge[T: Type](other: Term)(using quotes: Quotes) = {
      import quotes.reflect._

      // Terms must both have the same name
      if (this.name != other.name)
        report.throwError(s"Cannot resolve coproducts because terms ${this} and ${other} have different names") // TODO Describe this as better error messages for users?

      if (this.optional != other.optional)
        report.throwError(s"Cannot resolve coproducts because one of the terms ${this} and ${other} is optional and the other is not")

      if (this.typeType != other.typeType)
        report.throwError(s"Cannot resolve coproducts because the terms ${this} and ${other} have different types (${this.typeType} and ${other.typeType} respectively)")

      import GroupByOps._
      // Given Shape -> (Square, Rectangle) the result will be:
      // Shape.x detected multiple kinds of values: List(Term(x,Branch,List(Term(width,Leaf,List(),false), Term(height,Leaf,List(),false)),false), Term(x,Branch,List(Term(radius,Leaf,List(),false)),false))
      // Need to merge these terms
      val orderedGroupBy = (this.children ++ other.children).groupByOrdered(_.name)
      // Validate the keys to make sure that they are all the same kind of thing. E.g. if you have a Shape coproduct
      // with a Square.height and a Rectagnle.height, both 'height' fields but be a Leaf (and also in the future will need to have the same data type)
      // TODO Need to add datatype to Term so we can also verify types are the same for the coproducts
      val newChildren =
        orderedGroupBy.map((term, values) => {
          val distinctValues = values.distinct
          if (distinctValues.length > 1)
            report.throwError(s"Invalid coproduct at: ${TypeRepr.of[T].widen.typeSymbol.name}.${term} detected multiple kinds of values: ${distinctValues}")
          
          // TODO Check if there are zero?
          distinctValues.head
        }).toList

      this.copy(children = newChildren)
    }

  }

  object Term {
    import io.getquill.ast._

    // Not sure if Branch Terms should have hidden properties
    def property(parent: Ast, name: String, termType: TermType) =
      Property.Opinionated(parent, name, Renameable.neutral, 
        termType match {
          case Leaf => Visible
          case Branch => Hidden
        }
      )

    def toAst(node: Term, parent: Ast): List[(Ast, String)] =
      node match {
        // If there are no children, property should not be hidden i.e. since it's not 'intermediate'
        // I.e. for the sake of SQL we set properties representing embedded objects to 'hidden'
        // so they don't show up in the SQL.
        case Term(name, tt, Nil, _) =>
          val output = List(property(parent, name, tt))
          // Add field name to the output: x.width -> (x.width, width)
          output.map(ast => (ast, name))

        
        case Term(name, tt, list, false) =>
          val output = 
            list.flatMap(elem => 
              toAst(elem, property(parent, name, tt)))
          // Add field name to the output
          output.map((ast, childName) => (ast, name + childName))
          
        // Leaflnode in an option
        case Term(name, Leaf, list, true) =>
          val idV = Ident("v", Quat.Generic) // TODO Specific quat inference
          val output =
            for {
              elem <- list
              (newAst, subName) <- toAst(elem, idV)
            } yield (OptionMap(property(parent, name, Leaf), idV, newAst), subName)
          // Add field name to the output
          output.map((ast, childName) => (ast, name + childName))

        // Product node in a option
        case Term(name, Branch, list, true) =>
          val idV = Ident("v", Quat.Generic)
          val output = 
            for {
              elem <- list
              (newAst, subName) <- toAst(elem, idV)
            } yield (OptionTableMap(property(parent, name, Branch), idV, newAst), subName)

          // Add field name to the output
          output.map((ast, childName) => (ast, name + childName))
      }

    /**
     * Top-Level expansion of a Term is slighly different the later levels. A the top it's always ident.map(id => ...)
     * if Ident is an option as opposed to OptionMap which it would be, in lower layers.  
     *
     * Legend: 
     *   T(x, [y,z])      := Term(x=name, children=List(y,z)), T-Opt=OptionalTerm I.e. term where optional=true
     *   P(a, b)          := Property(a, b) i.e. a.b
     *   M(a, v, P(v, b)) := Map(a, v, P(v, b)) or m.map(v => P(v, b))
     */
    def toAstTop(node: Term): List[(Ast, String)] = {
      node match {
        // Node without children
        // If leaf node, return the term, don't care about if it is optional or not
        case Term(name, _, Nil, _) =>
          val output = List(Ident(name, Quat.Generic))
          // For a single-element field, add field name to the output
          output.map(ast => (ast, name))

        // Product node not inside an option. 
        // T( a, [T(b), T(c)] ) => [ a.b, a.c ] 
        // (done?)         => [ P(a, b), P(a, c) ] 
        // (recurse more?) => [ P(P(a, (...)), b), P(P(a, (...)), c) ]
        // where T is Term and P is Property (in Ast) and [] is a list
        case Term(name, _, list, false) =>
          val output = list.flatMap(elem => toAst(elem, Ident(name, Quat.Generic)))
          // Do not add top level field to the output. Otherwise it would be x -> (x.width, xwidth), (x.height, xheight)
          output

        // Production node inside an Option
        // T-Opt( a, [T(b), T(c)] ) => 
        // [ a.map(v => v.b), a.map(v => v.c) ] 
        // (done?)         => [ M( a, v, P(v, b)), M( a, v, P(v, c)) ]
        // (recurse more?) => [ M( P(a, (...)), v, P(v, b)), M( P(a, (...)), v, P(v, c)) ]
        case Term(name, _, list, true) =>
          val idV = Ident("v", Quat.Generic)
          val output = 
            for {
              elem <- list
              (newAst, name) <- toAst(elem, idV)
              // TODO Is this right? Should it be OptionTableMap?
            } yield (Map(Ident(name, Quat.Generic), idV, newAst), name)
          // Do not add top level field to the output. Otherwise it would be x -> (x.width, xwidth), (x.height, xheight)
          output
      }
    }
  }

  class TypeExtensions(using Quotes) { self =>
    import quotes.reflect._
    
    implicit class TypeExt(tpe: Type[_]) {
      def constValue = self.constValue(tpe)
      def isProduct = self.isProduct(tpe)
      def notOption = self.notOption(tpe)
    }

    def constValue(tpe: Type[_]): String =
      TypeRepr.of(using tpe) match {
        case ConstantType(IntConstant(value)) => value.toString
        case ConstantType(StringConstant(value)) => value.toString
        // Macro error
      }
    def isProduct(tpe: Type[_]): Boolean =
      TypeRepr.of(using tpe) <:< TypeRepr.of[Product]
    def notOption(tpe: Type[_]): Boolean =
      !(TypeRepr.of(using tpe) <:< TypeRepr.of[Option[Any]])
  }

  /** Go through all possibilities that the element might be and collect their fields */
  def collectFields[Fields, Types](node: Term, fieldsTup: Type[Fields], typesTup: Type[Types])(using Quotes): List[Term] = {
    import quotes.reflect.{Term => QTerm, _}
    val ext = new TypeExtensions
    import ext._
    
    (fieldsTup, typesTup) match {
      case ('[field *: fields], '[tpe *: types]) if Type.of[tpe].isProduct =>
        base[tpe](node) :: collectFields(node, Type.of[fields], Type.of[types])
      case (_, '[EmptyTuple]) => Nil
      case _ => report.throwError("Cannot Derive Sum during Type Flattening of Expression:\n" + (fieldsTup, typesTup))
    }
  }

  def flatten[Fields, Types](node: Term, fieldsTup: Type[Fields], typesTup: Type[Types])(using Quotes): List[Term] = {
    import quotes.reflect.{Term => QTerm, _}
    val ext = new TypeExtensions
    import ext._

    def constValue[T: Type]: String =
      TypeRepr.of[T] match {
        case ConstantType(IntConstant(value)) => value.toString
        case ConstantType(StringConstant(value)) => value.toString
        // Macro error
      }

    (fieldsTup, typesTup) match {
      // TODO These calls are expensive
      // do this first '[field *: fields], then do '[Option[tpe] *: types] internally

      case ('[field *: fields], '[Option[tpe] *: types]) if Type.of[tpe].isProduct =>
        val childTerm = Term(Type.of[field].constValue, Branch, optional = true)
        //println(s"------ Optional field expansion ${Type.of[field].constValue.toString}:${TypeRepr.of[tpe].show} is a product ----------")
        base[tpe](childTerm) :: flatten(node, Type.of[fields], Type.of[types])

      case ('[field *: fields], '[tpe *: types]) if Type.of[tpe].isProduct && Type.of[tpe].notOption  =>
        val childTerm = Term(Type.of[field].constValue, Branch)
        //println(s"------ Non-Optional field expansion ${Type.of[field].constValue.toString}:${TypeRepr.of[tpe].show} is a product ----------")
        base[tpe](childTerm) :: flatten(node, Type.of[fields], Type.of[types])

      case ('[field *: fields], '[Option[tpe] *: types]) =>
        val childTerm = Term(Type.of[field].constValue, Leaf, optional = true)
        //println(s"------ Optional field expansion ${Type.of[field].constValue.toString}:${TypeRepr.of[tpe].show} is a Leaf ----------")
        childTerm :: flatten(node, Type.of[fields], Type.of[types])

      case ('[field *: fields], '[tpe *: types]) if Type.of[tpe].notOption =>
        val childTerm = Term(Type.of[field].constValue, Leaf)
        println(s"------ Non-Optional field expansion ${Type.of[field].constValue.toString}:${TypeRepr.of[tpe].show} is a Leaf ----------")
        childTerm :: flatten(node, Type.of[fields], Type.of[types])

      case (_, '[EmptyTuple]) => Nil

      case _ => report.throwError("Cannot Derive Product during Type Flattening of Expression:\n" + (fieldsTup, typesTup))
    } 
  }

  def base[T: Type](term: Term)(using Quotes): Term = {
    import quotes.reflect.{Term => QTerm, _}

    // if there is a decoder for the term, just return the term
    Expr.summon[Mirror.Of[T]] match {
      case Some(ev) => {
        // Otherwise, recursively summon fields
        ev match {
          case '{ $m: Mirror.ProductOf[T] { type MirroredElemLabels = elementLabels; type MirroredElemTypes = elementTypes }} =>
            val children = flatten(term, Type.of[elementLabels], Type.of[elementTypes])
            term.withChildren(children)


          // TODO Make sure you can summon a ColumnResolver if there is a SumMirror, otherwise this kind of decoding should be impossible
          case '{ $m: Mirror.SumOf[T] { type MirroredElemLabels = elementLabels; type MirroredElemTypes = elementTypes }} =>
            // Find field infos (i.e. Term objects) for all potential types that this coproduct could be
            val alternatives = collectFields(term, Type.of[elementLabels], Type.of[elementTypes])
            // Then merge them together to get one term representing all of their fields types.
            // Say you have a coproduct Shape -> (Square(width), Rectangle(width,height), Circle(radius))
            // You would get Term(width, height, radius)
            alternatives.reduce((termA, termB) => termA.merge[T](termB))
            
          case _ =>
            Expr.summon[GenericDecoder[_, T]] match {
              case Some(decoder) => term
              case _ => report.throwError("Cannot Find Decoder or Expand a Product of the Type:\n" + ev.show)
            }
        }
      }
      case None => 
        Expr.summon[GenericDecoder[_, T]] match {
          case Some(decoder) => term
          case None => report.throwError(s"Cannot find derive or summon a decoder for ${Type.show[T]}")
        }
    }
  }  

  private def productized[T: Type](using Quotes): Ast = {
    val lifted = base[T](Term("x", Branch)).toAst
    val insert =
      if (lifted.length == 1)
        lifted.head._1
      else {
        CaseClass(lifted.map((ast, name) => (name, ast)))
      }
    insert
  }
  
  // ofStaticAst
  /** ElaborateStructure the query AST in a static query **/
  def ontoAst[T](ast: Ast)(using Quotes, Type[T]): AMap = {
    val bodyAst = productized[T]
    AMap(ast, Ident("x", Quat.Generic), bodyAst)
  }

  /** ElaborateStructure the query AST in a dynamic query **/
  def ontoDynamicAst[T](queryAst: Expr[Ast])(using Quotes, Type[T]): Expr[AMap] = {
    val bodyAst = productized[T]
    '{ AMap($queryAst, Ident("x", Quat.Generic), ${Lifter(bodyAst)}) }
  }

  def ofProductType[T: Type](baseName: String)(using Quotes): List[Ast] = {
    val expanded = base[T](Term(baseName, Branch))
    expanded.toAst.map(_._1)
  }


  /**
   * 
   * 
   * Example:
   *   case class Person(name: String, age: Option[Int])
   *   val p = Person("Joe")
   *   lift(p)
   * Output: 
   *   Quote(ast: CaseClass("name" -> ScalarTag(name), lifts: EagerLift(p.name, name))
   * 
   * Example:
   *   case class Name(first: String, last: String)
   *   case class Person(name: Name)
   *   val p = Person(Name("Joe", "Bloggs"))
   *   lift(p)
   * Output: 
   *   Quote(ast: CaseClass("name" -> CaseClass("first" -> ScalarTag(namefirst), "last" -> ScalarTag(last)), 
   *         lifts: EagerLift(p.name.first, namefirst), EagerLift(p.name.last, namelast))
   * 
   * Note for examples below:
   *  idA := "namefirst"
   *  idB := "namelast"
   * 
   * Example:
   *   case class Name(first: String, last: String)
   *   case class Person(name: Option[Name])
   *   val p = Person(Some(Name("Joe", "Bloggs")))
   *   lift(p)
   * Output: 
   *   Quote(ast:   CaseClass("name" -> OptionSome(CaseClass("first" -> ScalarTag(idA), "last" -> ScalarTag(idB))), 
   *         lifts: EagerLift(p.name.map(_.first), namefirst), EagerLift(p.name.map(_.last), namelast))
   * 
   * Alternatively, the case where it is:
   *   val p = Person(None) the AST and lifts remain the same, only they effectively become None for every value:
   *         lifts: EagerLift(None               , idA), EagerLift(None              , idB))
   * 
   * Legend: x:a->b := Assignment(Ident("x"), a, b)
   */
  // TODO Should have specific tests for this function indepdendently
  // keep namefirst, namelast etc.... so that testability is easier due to determinism
  // re-key by the UIDs later
  def ofProductValue[T: Type](productValue: Expr[T])(using Quotes): TaggedLiftedCaseClass = {

    def toAst(node: Term): (String, Ast) =
      def toAstRec(node: Term, parentTerm: String, topLevel: Boolean = false): (String, Ast) =
        def notTopLevel(str: String) = if (topLevel) "" else str
        node match
          case Term(name, Leaf, _, _) =>
            (name, ScalarTag(parentTerm + name))
          // On the top level, parent is "", and 1st parent in recursion is also ""
          case Term(name, Branch, list, false) =>
            val children = list.map(child => toAstRec(child, notTopLevel(parentTerm + name)))
            (name, CaseClass(children))
          // same logic for optionals
          case Term(name, Branch, list, true) =>
            val children = list.map(child => toAstRec(child, notTopLevel(parentTerm + name)))
            (name, OptionSome(CaseClass(children)))
          case _ =>
            quotes.reflect.report.throwError(s"Illegal derived schema: $node from type ${Type.of[T]}", productValue)
      toAstRec(node, "", true)

    val expanded = base[T](Term("notused", Branch))
    // create a nested AST for the Term nest with the expected scalar tags inside
    val (_, nestedAst) = toAst(expanded)
    // create the list of (label, lift) for the expanded entities
    val lifts = DeconstructElaboratedEntity(expanded, productValue).map((k,v) => (v,k))
    // return nested AST keyed by the lifts list
    TaggedLiftedCaseClass(nestedAst, lifts)
  }

  extension [T](opt: Option[T])
    def getOrThrow(msg: String) = opt.getOrElse { throw new IllegalArgumentException(msg) }

  case class TaggedLiftedCaseClass(caseClass: Ast, lifts: List[(String, Expr[_])]) {
    import java.util.UUID
    def uuid() = UUID.randomUUID.toString

    /** Replace keys of the tagged lifts with proper UUIDs */
    def reKeyWithUids(): TaggedLiftedCaseClass = {
      def replaceKeys(newKeys: Map[String, String]): Ast =
        Transform(caseClass) {
          case ScalarTag(keyName) => 
            lazy val msg = s"Cannot find key: '${keyName}' in the list of replacements: ${newKeys}"
            ScalarTag(newKeys.get(keyName).getOrThrow(msg))
        }
      
      val oldAndNewKeys = lifts.map((key, expr) => (key, uuid(), expr))
      val keysToNewKeys = oldAndNewKeys.map((key, newKey, _) => (key, newKey)).toMap
      val newNewKeysToLifts = oldAndNewKeys.map((_, newKey, lift) => (newKey, lift))
      val newAst = replaceKeys(keysToNewKeys)
      TaggedLiftedCaseClass(newAst, newNewKeysToLifts)
    }
  }
}
