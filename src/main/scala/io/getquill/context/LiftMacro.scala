package io.getquill.context

import scala.language.higherKinds
import scala.language.experimental.macros
import java.io.Closeable
import scala.compiletime.summonFrom
import scala.util.Try
import io.getquill.{ ReturnAction }
import io.getquill.dsl.EncodingDsl
import io.getquill.quoter.Quoted
import io.getquill.quoter.QueryMeta
import io.getquill.derived._
import io.getquill.context.mirror.MirrorDecoders
import io.getquill.context.mirror.Row
import io.getquill.dsl.GenericDecoder
import io.getquill.quoter.Planter
import io.getquill.ast.{ Ast, Ident => AIdent }
import io.getquill.ast.ScalarTag
import io.getquill.idiom.Idiom
import io.getquill.ast.{Transform, QuotationTag}
import io.getquill.quoter.QuotationLot
import io.getquill.quoter.QuotedExpr
import io.getquill.quoter.PlanterExpr
import io.getquill.idiom.ReifyStatement
import io.getquill.quoter.EagerPlanter
import io.getquill.quoter.LazyPlanter
import io.getquill.dsl.GenericEncoder
import io.getquill.derived.ElaborateQueryMeta
import io.getquill.quat.Quat
import scala.quoted._
import io.getquill._
import io.getquill.quat.QuatMaking

object LiftMacro {
  private[getquill] def newUuid = java.util.UUID.randomUUID().toString
  private[getquill] val VIdent = AIdent("v", Quat.Generic)

  def apply[T: Type, PrepareRow: Type](vvv: Expr[T])(using Quotes): Expr[T] = {
    import quotes.reflect._

    // check if T is a case-class (e.g. mirrored entity) or a leaf, probably best way to do that
    QuatMaking.ofType[T] match
      //case Quat.Product => liftProduct(vvv)
      case _ => liftValue[T, PrepareRow](vvv)
  }

  
  // private[getquill] def liftProduct[T, PrepareRow](vvv: Expr[T])(using qctx:Quotes, tpe: Type[T], prepareRowTpe: Type[PrepareRow]): Expr[T] = {
  //   import qctx.reflect._
  //   val lifts: List[(String, Ast, Expr[_])] = ElaborateQueryMeta.staticListWithLifts[T]("x", vvv)
  //   val assignmentsAndLifts = 
  //     lifts.map(
  //       (str, astPath, lift) => 
  //         // since we don't have an implicit Type for every single lift, we need to pull out each of their TypeReprs convert them to Type and manually pass them in
  //         val liftType = lift.asTerm.tpe.asType
  //         val liftUuid = newUuid
  //         val liftValue = LiftMacro.liftValue(lift, liftUuid)(using liftType, prepareRowTpe, quotes)
  //         // Each assignemnt will point to a scalar tag that contains the actual value of the corresponding field of the lifted case-class
  //         // TODO Need to check this works for embedded case classes
  //         val astAssignment = Assignment(VIdent, astPath, ScalarTag(liftUuid))
  //     )
  //   null
  // }

  private[getquill] def liftValue[T: Type, PrepareRow: Type](vvv: Expr[T], uuid: String = newUuid)(using Quotes): Expr[T] = {
    import quotes.reflect._
    val encoder = 
      Expr.summon[GenericEncoder[T, PrepareRow]] match
        case Some(enc) => enc
        case None => report.throwError(s"Cannot Find a ${TypeRepr.of[T]} Encoder of ${Printer.TreeShortCode.show(vvv.asTerm)}", vvv)

    '{ EagerPlanter($vvv, $encoder, ${Expr(uuid)}).unquote } //[T, PrepareRow] // adding these causes assertion failed: unresolved symbols: value Context_this
  }

  def applyLazy[T, PrepareRow](vvv: Expr[T])(using Quotes, Type[T], Type[PrepareRow]): Expr[T] = {
    import quotes.reflect._
    val uuid = java.util.UUID.randomUUID().toString
    '{ LazyPlanter($vvv, ${Expr(uuid)}).unquote } //[T, PrepareRow] // adding these causes assertion failed: unresolved symbols: value Context_this
  }
}
