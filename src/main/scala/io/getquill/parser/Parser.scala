package io.getquill.parser

import io.getquill.ast.{Ident => AIdent, Query => AQuery, _}
import io.getquill.quoter._
import scala.quoted._
import scala.quoted.{Const => ConstExpr}
import scala.annotation.StaticAnnotation
import scala.deriving._
import io.getquill.Embedable
import io.getquill.quoter.Dsl
import scala.reflect.ClassTag
import io.getquill.norm.capture.AvoidAliasConflict
import io.getquill.quoter.QuotationLotExpr
import io.getquill.EntityQuery
import io.getquill.Query
import io.getquill.Format
import io.getquill.parser.ParserHelpers._
import io.getquill.quat.QuatMaking
import io.getquill.quat.Quat
import io.getquill.EntityQueryModel

type Parser[R] = PartialFunction[quoted.Expr[_], R]
type SealedParser[R] = (quoted.Expr[_] => R)

/**
  * Parse Quill-DSL expressions into the Quill AST.
  *
  * Naming Legend:
  *  - AIdent/AQuery/etc... means Quill Ast-Ident/Query...
  *  - TIdent/TConstant/ec... means Tasty Ident/Constant/etc...
  */
object Parser {
  val empty: Parser[Ast] = PartialFunction.empty[Expr[_], Ast]

  //Error check
  def throwExpressionError(expr: Expr[_], astClass: Class[_])(using Quotes) =
    import quotes.reflect._
    report.throwError(s"""|
      |s"==== Tree cannot be parsed to '${astClass.getSimpleName}' ===
      |  ${Format(Printer.TreeShortCode.show(expr.asTerm)) /* Or Maybe just expr? */}
      |==== Extractors ===
      |  ${Format(Printer.TreeStructure.show(expr.asTerm))}
      |==== Tree ===
      |  ${printer.str(expr.asTerm)}
      |""".stripMargin, 
      expr)
  

  object Implicits {

    //https://docs.scala-lang.org/overviews/core/implicit-classes.html
    //This keyword makes the class’s primary constructor available for implicit conversions when the class is in scope.
    implicit class ParserExtensions[R](parser: Parser[R])(implicit val qctx: Quotes, ct: ClassTag[R]) {
      import quotes.reflect._

      def seal: SealedParser[R] = 
        (expr: Expr[_]) => parser.lift(expr).getOrElse {
          throwExpressionError(expr, ct.runtimeClass)
        }        
    }

  }

  trait Delegated[R] extends Parser[R] with TastyMatchers {
    override implicit val qctx: Quotes
    def delegate: PartialFunction[Expr[_], R]

    //apply() is an abstract member of PartialFunction
    override def apply(expr: Expr[_]): R = {
      delegate.apply(expr)
    }

    //isDefinedAt() is an abstract member of PartialFunction
    //Checks if a value is contained in the function's domain.
    def isDefinedAt(expr: Expr[_]): Boolean = {
      delegate.isDefinedAt(expr)
    }

  }

  trait Clause[R](using override val qctx: Quotes) extends Delegated[R] with TastyMatchers with Idents with QuatMaking { base =>
    import Implicits._

    def root: Parser[Ast]
    def astParse = root.seal
    def reparent(root: Parser[Ast]): Clause[R]
  }

  case class Series private (children: List[Clause[Ast]] = List())(override implicit val qctx: Quotes) extends Delegated[Ast] { self =>
    
    def delegate = composite
    
    def composite: PartialFunction[Expr[_], Ast] =
      children.map(child => child.reparent(this)).foldRight(PartialFunction.empty[Expr[_], Ast])(_ orElse _)
    
    //Is this to chain parsers?
    def combine(other: Series): Series =
      Series(self.children ++ other.children)
  }

  object Series {
    def single(clause: Clause[Ast])(using Quotes): Series = Series(List(clause))
  }
}

trait ParserFactory {
  def apply(using Quotes): Parser[Ast]
}
trait ParserLibrary extends ParserFactory {
  import Parser._

  def quotationParser(using qctx: Quotes)          = Series.single(new QuotationParser)
  def queryParser(using qctx: Quotes)              = Series.single(new QueryParser)
  def patMatchParser(using qctx: Quotes)           = Series.single(new CasePatMatchParser)
  def functionParser(using qctx: Quotes)           = Series.single(new FunctionParser)
  def functionApplyParser(using qctx: Quotes)      = Series.single(new FunctionApplyParser)
  def valParser(using qctx: Quotes)                = Series.single(new ValParser)
  def blockParser(using qctx: Quotes)              = Series.single(new BlockParser)
  def operationsParser(using qctx: Quotes)         = Series.single(new OperationsParser)
  def genericExpressionsParser(using qctx: Quotes) = Series.single(new GenericExpressionsParser)
  def actionParser(using qctx: Quotes)             = Series.single(new ActionParser)
  def optionParser(using qctx: Quotes)             = Series.single(new OptionParser)
  def valueParser(using qctx: Quotes)              = Series.single(new ValueParser)

  // def userDefined(using qctxInput: Quotes) = Series(new Glosser[Ast] {
  //   val qctx = qctxInput
  //   def apply(root: Parser[Ast]) = PartialFunction.empty[Expr[_], Ast]
  // })

  //Everything needs to be parsed from a quoted state, and sent to the subsequent parser
  def apply(using Quotes): Parser[Ast] =
    quotationParser
        .combine(queryParser)
        .combine(optionParser)
        .combine(actionParser)
        .combine(functionParser) // decided to have it be it's own parser unlike Quill3
        .combine(patMatchParser)
        .combine(valParser)
        .combine(blockParser)
        .combine(operationsParser)
        .combine(valueParser) // must go before functionApplyParser since valueParser parsers '.apply on case class' and the functionApply would take that
        .combine(functionApplyParser) // must go before genericExpressionsParser otherwise that will consume the 'apply' clauses
        .combine(genericExpressionsParser)
}

object ParserLibrary extends ParserLibrary

case class FunctionApplyParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] {
  import quotes.reflect._
  import Parser.Implicits._
  import io.getquill.norm.capture.AvoidAliasConflict
  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)

  //case q"new { def apply[..t1](...$params) = $body }" =>
  //  c.fail("Anonymous classes aren't supported for function declaration anymore. Use a method with a type parameter instead. " +
  //    "For instance, replace `val q = quote { new { def apply[T](q: Query[T]) = ... } }` by `def q[T] = quote { (q: Query[T] => ... }`")

  def delegate: PartialFunction[Expr[_], Ast] = {
    case Unseal(Apply(Select(term, "apply"), args)) =>
      FunctionApply(astParse(term.asExpr), args.map(arg => astParse(arg.asExpr)))
  }
}


case class FunctionParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] {
  import quotes.reflect._
  import Parser.Implicits._
  import io.getquill.norm.capture.AvoidAliasConflict
  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)

  //case q"new { def apply[..t1](...$params) = $body }" =>
  //  c.fail("Anonymous classes aren't supported for function declaration anymore. Use a method with a type parameter instead. " +
  //    "For instance, replace `val q = quote { new { def apply[T](q: Query[T]) = ... } }` by `def q[T] = quote { (q: Query[T] => ... }`")

  def delegate: PartialFunction[Expr[_], Ast] = {
    case Unseal(RawLambdaN(params, body)) =>
      val subtree = Function(params.map((name, tpe) => cleanIdent(name, tpe)), astParse(body.asExpr))
      // If there are actions inside the subtree, we need to do some additional sanitizations
      // of the variables so that their content will not collide with code that we have generated.

      // TODO Add back once moved to the quill subtree because AvoidAliasConflict is private[getquill]
      //if (CollectAst.byType[Action](subtree).nonEmpty)
      //  AvoidAliasConflict.sanitizeVariables(subtree, dangerousVariables)
      //else
      subtree
  }
}

case class ValParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] with PatternMatchingValues {
  import quotes.reflect._
  import Parser.Implicits._
  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)

  def delegate: PartialFunction[Expr[_], Ast] = {
    case Unseal(ValDefTerm(ast)) => ast
  }
}

case class BlockParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] with PatternMatchingValues {
  import quotes.reflect.{Block => TBlock, _}
  import Parser.Implicits._
  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)

  def delegate: PartialFunction[Expr[_], Ast] = {
    case block @ Unseal(TBlock(parts, lastPart)) if (parts.length > 0) =>
      val partsAsts =
        parts.map {
          case term: Term => astParse(term.asExpr)
          case ValDefTerm(ast) => ast
          case other => 
            // TODO Better site-description in error (does other.show work?)
            report.throwError(s"Illegal statement ${other.show} in block ${block.show}")
        }
      val lastPartAst = astParse(lastPart.asExpr)
      Block((partsAsts :+ lastPartAst))
  }
}

case class CasePatMatchParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] with PatternMatchingValues {
  import quotes.reflect._
  import Parser.Implicits._
  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)

  def delegate: PartialFunction[Expr[_], Ast] = {
    case Unseal(PatMatchTerm(ast)) => ast
  }
}



// TODO Pluggable-in unlifter via implicit? Quotation dsl should have it in the root?
case class QuotationParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] {
  import quotes.reflect.{ Ident => TIdent, _}
  import Parser.Implicits._

  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)

  def delegate: PartialFunction[Expr[_], Ast] = {

    // // TODO Document this?
    // case Apply(TypeApply(Select(TIdent("Tuple2"), "apply"), List(Inferred(), Inferred())), List(Select(TIdent("p"), "name"), Select(TIdent("p"), "age"))) =>
    //   report.throwError("Matched here!")
    
    case QuotationLotExpr.Unquoted(quotationLot) =>
      quotationLot match {
        case Uprootable(uid, astTree, _, _, _, _) => Unlifter(astTree)
        case Pluckable(uid, astTree, _) => QuotationTag(uid)
        case Pointable(quote) => report.throwError(s"Quotation is invalid for compile-time or processing: ${quote.show}", quote)
      }

    case PlanterExpr.UprootableUnquote(expr) =>
      ScalarTag(expr.uid) // TODO Want special scalar tag for an encodeable scalar

    // A inline quotation can be parsed if it is directly inline. If it is not inline, a error
    // must happen (specifically have a check for it or just fail to parse?) 
    // since we would not know the UID since it is not inside of a bin. This situation
    // should only be encountered to a top-level quote passed to the 'run' function and similar situations.
    case QuotedExpr.Uprootable(quotedExpr) => // back here
      Unlifter(quotedExpr.ast) 
  }
}

case class ActionParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] with Assignments {
  import quotes.reflect.{Constant => TConstantant, _}
  import Parser.Implicits._
  
  def delegate: PartialFunction[Expr[_], Ast] = {
    del
  }

  def del: PartialFunction[Expr[_], Ast] = {
    case '{ type t; ($query: EntityQueryModel[`t`]).insert(($first: `t`=>(Any,Any)), (${Varargs(others)}: Seq[`t` => (Any, Any)]): _*) } =>
      println("****************** Insert Parsed Here ***********")
      val insertAssignments = first.asTerm +: others.map(_.asTerm)
      val assignments = insertAssignments.filterNot(isNil(_)).map(a => AssignmentTerm.OrFail(a))
      Insert(astParse(query), assignments.toList)
    case '{ type t; ($query: EntityQueryModel[`t`]).update(($first: `t`=>(Any,Any)), (${Varargs(others)}: Seq[`t` => (Any, Any)]): _*) } =>
      println("****************** Update Parsed Here ***********")
      val updateAssignments = first.asTerm +: others.map(_.asTerm)
      val assignments = updateAssignments.filterNot(isNil(_)).map(a => AssignmentTerm.OrFail(a))
      Update(astParse(query), assignments.toList)
    case '{ type t; ($query: EntityQueryModel[`t`]).delete } =>
      println("****************** Delete Parsed Here ***********")
      Delete(astParse(query))

    // case Unseal(Update) =>
    //   Update(astParser(query), assignments.map(assignmentParser(_)))
    // case Unseal(Insert) =>
    //   Insert(astParser(query), assignments.map(assignmentParser(_)))
    // case Unseal(Delete) =>
    //   Delete(astParser(query))
    // case Unseal(Returning_NoArguments) =>
    //   c.fail(s"A 'returning' clause must have arguments.")
    // case Unseal(Returning) =>
    //   val ident = identParser(alias)
    //   val bodyAst = reprocessReturnClause(ident, astParser(body), action)
    //   // Verify that the idiom supports this type of returning clause
    //   idiomReturnCapability match {
    //     case ReturningMultipleFieldSupported | ReturningClauseSupported | OutputClauseSupported =>
    //     case ReturningSingleFieldSupported =>
    //       c.fail(s"The 'returning' clause is not supported by the ${currentIdiom.getOrElse("specified")} idiom. Use 'returningGenerated' instead.")
    //     case ReturningNotSupported =>
    //       c.fail(s"The 'returning' or 'returningGenerated' clauses are not supported by the ${currentIdiom.getOrElse("specified")} idiom.")
    //   }
    //   // Verify that the AST in the returning-body is valid
    //   idiomReturnCapability.verifyAst(bodyAst)
    //   Returning(astParser(action), ident, bodyAst)
    // case Unseal(Returning_generated) =>
    //   val ident = identParser(alias)
    //   val bodyAst = reprocessReturnClause(ident, astParser(body), action)
    //   // Verify that the idiom supports this type of returning clause
    //   idiomReturnCapability match {
    //     case ReturningNotSupported =>
    //       c.fail(s"The 'returning' or 'returningGenerated' clauses are not supported by the ${currentIdiom.getOrElse("specified")} idiom.")
    //     case _ =>
    //   }
    //   // Verify that the AST in the returning-body is valid
    //   idiomReturnCapability.verifyAst(bodyAst)
    //   ReturningGenerated(astParser(action), ident, bodyAst)
    // case Unseal(Foreach) =>
    //   AvoidAliasConflict.sanitizeVariables(Foreach(astParser(query), identParser(alias), astParser(body)), dangerousVariables)

    // case Unseal(Apply(Select(query, "insert"), insertAssignments)) =>
    //   val assignments = insertAssignments.filterNot(isNil(_)).map(a => AssignmentTerm.OrFail(a))
    //   Insert(astParse(query.asExpr), assignments)
  }

  private def isNil(term: Term): Boolean =
    Untype(term) match {
      case Repeated(Nil, any) => true
      case _ => false
    }

  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)
}

case class OptionParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] {
  import qctx.reflect.{Constant => TConstantant, _}
  import Parser.Implicits._

  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)

  def delegate: PartialFunction[Expr[_], Ast] = {
    case '{ ($o: Option[t]).isEmpty } => OptionIsEmpty(astParse(o))

    case '{ ($o: Option[t]).map(${Lambda1(id, idType, body)}) } => 
      if (is[Product](o)) OptionTableMap(astParse(o), cleanIdent(id, idType), astParse(body))
      else OptionMap(astParse(o), cleanIdent(id, idType), astParse(body))
    
    case '{ ($o: Option[t]).exists(${Lambda1(id, idType, body)}) } =>
      if (is[Product](o)) OptionTableExists(astParse(o), cleanIdent(id, idType), astParse(body))
      else OptionExists(astParse(o), cleanIdent(id, idType), astParse(body))
  }
}

case class QueryParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] with PropertyAliases {
  import qctx.reflect.{Constant => TConstantant, _}
  import Parser.Implicits._

  def delegate: PartialFunction[Expr[_], Ast] = {
    del
  }

  def del: PartialFunction[Expr[_], Ast] = {

  // This seems to work?
    case '{ type t; EntityQuery.apply[`t`] } => //: EntityQuery[`$t`]
      val tpe = TypeRepr.of[t]
      val name: String = tpe.classSymbol.get.name
      Entity(name, List(), InferQuat.ofType(tpe).probit)

    case '{ Dsl.querySchema[t](${ConstExpr(name: String)}, ${GenericSeq(properties)}: _*) } =>
      println("Props are: " + properties.map(_.show))
      val output = Entity.Opinionated(name, properties.toList.map(PropertyAliasExpr.OrFail[t](_)), InferQuat.of[t].probit, Renameable.Fixed)
      printer.lnf(output)
      output

    case '{ ($q:Query[qt]).map[mt](${Lambda1(ident, tpe, body)}) } => 
      Map(astParse(q), cleanIdent(ident, tpe), astParse(body))

    case '{ ($q:Query[qt]).flatMap[mt](${Lambda1(ident, tpe, body)}) } => 
      FlatMap(astParse(q), cleanIdent(ident, tpe), astParse(body))

    case '{ ($q:Query[qt]).filter(${Lambda1(ident, tpe, body)}) } => 
      Filter(astParse(q), cleanIdent(ident, tpe), astParse(body))

    case '{ ($q:Query[qt]).withFilter(${Lambda1(ident, tpe, body)}) } => 
      Filter(astParse(q), cleanIdent(ident, tpe), astParse(body))

    case '{ ($a: Query[t]).union($b) } => Union(astParse(a), astParse(b))

    case '{ type t1; type t2; ($q1: Query[`t1`]).join[`t1`, `t2`](($q2: Query[`t2`])).on(${Lambda2(ident1, tpe1, ident2, tpe2, on)}) } => Join(InnerJoin, astParse(q1), astParse(q2), cleanIdent(ident1, tpe1), cleanIdent(ident2, tpe2), astParse(on))
    case '{ type t1; type t2; ($q1: Query[`t1`]).leftJoin[`t1`, `t2`](($q2: Query[`t2`])).on(${Lambda2(ident1, tpe1, ident2, tpe2, on)}) } => Join(InnerJoin, astParse(q1), astParse(q2), cleanIdent(ident1, tpe1), cleanIdent(ident2, tpe2), astParse(on))
    case '{ type t1; type t2; ($q1: Query[`t1`]).rightJoin[`t1`, `t2`](($q2: Query[`t2`])).on(${Lambda2(ident1, tpe1, ident2, tpe2, on)}) } => Join(InnerJoin, astParse(q1), astParse(q2), cleanIdent(ident1, tpe1), cleanIdent(ident2, tpe2), astParse(on))
    case '{ type t1; type t2; ($q1: Query[`t1`]).fullJoin[`t1`, `t2`](($q2: Query[`t2`])).on(${Lambda2(ident1, tpe1, ident2, tpe2, on)}) } => Join(InnerJoin, astParse(q1), astParse(q2), cleanIdent(ident1, tpe1), cleanIdent(ident2, tpe2), astParse(on))

    case '{ type t1; ($q1: Query[`t1`]).join[`t1`](${Lambda1(ident1, tpe, on)}) } => 
      FlatJoin(InnerJoin, astParse(q1), cleanIdent(ident1, tpe), astParse(on))
    case '{ type t1; ($q1: Query[`t1`]).leftJoin[`t1`](${Lambda1(ident1, tpe, on)}) } => 
      FlatJoin(LeftJoin, astParse(q1), cleanIdent(ident1, tpe), astParse(on))
  }

  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)
}

// case class ConflictParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] {
//   import quotes.reflect.{Constant => TConstantant, using,  _}
//   import Parser.Implicits._
//   def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)

//   //  case q"$query.map[mt]((x) => y) }"
//   //case '{ ($q:Query[qt]).map[mt](${Lambda1(ident, body)}) } => 

//   def delegate: PartialFunction[Expr[_], Ast] = {
//     // case q"$query.onConflictIgnore" =>
//     //  OnConflict(astParser(query), OnConflict.NoTarget, OnConflict.Ignore) 
//     case '{ ($query:io.getquill.Insert[qt]).onConflictIgnore } =>
//       OnConflict(astParse(query), OnConflict.NoTarget, OnConflict.Ignore) 
//   }  
// }

case class OperationsParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] {
  import quotes.reflect._
  import QueryDsl._
  import io.getquill.ast.Infix

  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)

  def delegate: PartialFunction[Expr[_], Ast] = {
    del.compose(PartialFunction.fromFunction(
      (expr: Expr[_]) => {
        expr
      }
    ))
  }

  //Handles named operations, ie Argument Operation Argument
  object NamedOp1 {
    def unapply(expr: Expr[_]): Option[(Expr[_], String, Expr[_])] =
      UntypeExpr(expr) match {
        case Unseal(Apply(Select(Untype(left), op: String), Untype(right) :: Nil)) => 
          Some(left.asExpr, op, right.asExpr)
        case _ => 
          None
      }
  }

  // TODO Is there any way to check if Numeric[T] exists if you don't know the type T
  // but know the type of the term?
  // def isNumeric(expr: Expr[Any]) = {
  //   val tpe = expr.unseal.tpe.seal
  //   Expr.summon(using tpe) match {
  //     case Some(_) => true
  //     case _ => false
  //   }
  // }

  def del: PartialFunction[Expr[_], Ast] = {
    case '{ ($str:String).like($other) } => 
      Infix(List(""," like ",""), List(astParse(str), astParse(other)), true, Quat.Value)

    case expr @ NamedOp1(left, "==", right) =>
      val leftAst = astParse(left)
      val rightAst = astParse(right)
      val res = BinaryOperation(leftAst, EqualityOperator.==, rightAst)
      res
    case NamedOp1(left, "||", right) =>
      BinaryOperation(astParse(left), BooleanOperator.||, astParse(right))
    case NamedOp1(left, "&&", right) =>
      BinaryOperation(astParse(left), BooleanOperator.&&, astParse(right))

    case '{ ($i: Int).toString } => astParse(i)
    
    // TODO .toInt should not have a cast, probably should rely on SQL auto conversion
    case '{ ($str: String).toInt } => Infix(List("CAST(", " AS Int)"), List(astParse(str)), true, Quat.Value)
    // TODO not sure how I want to do this on an SQL level. Maybe implement using SQL function containers since
    // they should be more dialect-portable then just infix
    case '{ ($str: String).length } => Infix(List("Len(",")"), List(astParse(str)), true, Quat.Value)


    //String Operations Cases

    //+
    case NamedOp1(left, "+", right) if is[String](left) || is[String](right) =>
      Console.println("String addition found");
      BinaryOperation(astParse(left), StringOperator.+, astParse(right))

    //toString
    case '{ ($i: String).toString } => astParse(i)

    //toUpperCase
    case '{ ($str:String).toUpperCase() } =>
      Console.println("String to uppercase found")
      Infix(List("ToUpperCase(", ")"), List(astParse(str)), true, Quat.Value)

    //toLowerCase
    case '{ ($str:String).toLowerCase() } =>
      Console.println("String to lowercase found")
      Infix(List("ToLowerCase(", ")"), List(astParse(str)), true, Quat.Value)

    //toLong

    //startsWith

    //split

    

    
    // 1 + 1
    // Apply(Select(Lit(1), +), Lit(1))
    // Expr[_] => BinaryOperation
    case NumericOperation(binaryOperation) =>
      Console.println("Numeric Operation");
      binaryOperation
  }

  object NumericOperation {
    def unapply(expr: Expr[_]): Option[BinaryOperation] = {
      UntypeExpr(expr) match {
        case NamedOp1(left, NumericOpLabel(binaryOp), right) if (is[Int](left, right)) =>
          Some(BinaryOperation(astParse(left), binaryOp, astParse(right)))
        case _ => None
      }
    }
  }

  object NumericOpLabel {
    def unapply(str: String): Option[BinaryOperator] = 
      str match {
        case "+" => Some(NumericOperator.`+`)
        case "-" => Some(NumericOperator.-)
        case "*" => Some(NumericOperator.*)
        case "/" => Some(NumericOperator./)
        case "%" => Some(NumericOperator.%)
        case ">" => Some(NumericOperator.`>`)
        case "<" => Some(NumericOperator.`<`)
        case _ => None
      }
  }
}

case class ValueParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] {
  import quotes.reflect.{Constant => TConstant, Ident => TIdent, _}
  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)

  def delegate: PartialFunction[Expr[_], Ast] = {
    // Parse Null values
    case Unseal(Literal(NullConstant())) => NullValue
    // Parse Constants
    case expr @ Unseal(ConstantTerm(v)) => Constant(v, InferQuat.ofExpr(expr))
    // Parse Option constructors
    case '{ Some.apply[t]($v) } => OptionSome(astParse(v))
    case '{ Option.apply[t]($v) } => OptionApply(astParse(v))
    case '{ None } => OptionNone(Quat.Null)
    case '{ Option.empty[t] } => OptionNone(InferQuat.of[t])

    // Parse tuples
    case Unseal(Apply(TypeApply(Select(TupleIdent(), "apply"), types), values)) => 
      Tuple(values.map(v => astParse(v.asExpr)))
    
    case CaseClassCreation(ccName, fields, args) =>
      if (fields.length != args.length) 
        throw new IllegalArgumentException(s"In Case Class ${ccName}, does not have the same number of fields (${fields.length}) as it does arguments ${args.length} (fields: ${fields}, args: ${args.map(_.show)})")
      val argsAst = args.map(astParse(_))
      CaseClass(fields.zip(argsAst))
      
    case id @ Unseal(i @ TIdent(x)) => 
      //cleanIdent(i.symbol.name, InferQuat.ofExpr(id))
      //println(s"@@@@@@@@@@@@@@@@@@@@@ Parsing ident: ${i.show}: ${i.tpe.widen}")
      cleanIdent(i.symbol.name, InferQuat.ofType(i.tpe))
  }
}

case class GenericExpressionsParser(root: Parser[Ast] = Parser.empty)(override implicit val qctx: Quotes) extends Parser.Clause[Ast] {
  import quotes.reflect.{Constant => TConstant, Ident => TIdent, _}
  def reparent(newRoot: Parser[Ast]) = this.copy(root = newRoot)

  def delegate: PartialFunction[Expr[_], Ast] = {

    case Unseal(value @ Select(Seal(prefix), member)) =>
      if (value.tpe <:< TypeRepr.of[io.getquill.Embedded]) { 
        Property.Opinionated(astParse(prefix), member, Renameable.ByStrategy, Visibility.Hidden)
      } else {
        //println(s"========= Parsing Property ${prefix.show}.${member} =========")
        Property(astParse(prefix), member)
      }

    // If at the end there's an inner tree that's typed, move inside and try to parse again
    case Unseal(Typed(innerTree, _)) =>
      astParse(innerTree.asExpr)

    case Unseal(Inlined(_, _, v)) =>
      //println("Case Inlined")
      //root.parse(v.asExprOf[T]) // With method-apply can't rely on it always being T?
      astParse(v.asExpr)
  }
}


