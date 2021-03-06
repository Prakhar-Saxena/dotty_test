// package io.getquill.derived

// import io.getquill.ast._
// import io.getquill.Spec
// import io.getquill.quat.Quat

// class ExpanderTest extends Spec {
//   import io.getquill.derived.ElaborateQueryMeta._
//   val body = Ident("body", Quat.Generic)

//   "correct expansion for" - {

//     "Person with Embedded Address" in {
//       case class Address(street: String)
//       case class Person(name: String, address: Address)
//       val exp = ElaborateQueryMeta.external[Person](body)
//     }
    
//     "Entity with Nestsed" in {
//       case class Nested(i: Int, l: Long)
//       case class Entity(a: String, b: Nested)
//       val ast = ElaborateQueryMeta.external[Entity](body)
//       ast.toString  mustEqual  "body.map(x => (x.a, x.b.i, x.b.l))"
//     }

//     "Nested with optional field" in {
//       case class Nested(i: Int)
//       case class Entity(a: Option[Nested])
//       val ast = ElaborateQueryMeta.external[Entity](body)
//       ast.toString  mustEqual  "body.map(x => x.a.map((v) => v.i))"
//     }

//     "Nested with optional and multiple fields" in {
//       case class Nested(i: Int, l: Long)
//       case class Entity(a: String, b: Option[Nested])
//       val ast = ElaborateQueryMeta.external[Entity](body)
//       ast.toString  mustEqual  "body.map(x => (x.a, x.b.map((v) => v.i), x.b.map((v) => v.l)))"
//     }

//     "Triple-nested with optional fields" in {
//       case class ReallyNested(foo: Int, bar: Int)
//       case class Nested(i: Int, l: Option[ReallyNested])
//       case class Entity(a: String, b: Option[Nested])
//       val ast = ElaborateQueryMeta.external[Entity](body)
//       ast.toString  mustEqual  "body.map(x => (x.a, x.b.map((v) => v.i), x.b.map((v) => v.l.map((v) => v.foo)), x.b.map((v) => v.l.map((v) => v.bar))))"
//     }

//     "Tuple" in {
//       val ast = ElaborateQueryMeta.external[(String, String)](body)
//       ast.toString  mustEqual  "body.map(x => (x._1, x._2))"
//     }

//     "Nested Tuple" in {
//       case class Entity(a: String, b: Int)
//       val ast = ElaborateQueryMeta.external[(String, Option[Entity])](body)
//       ast.toString  mustEqual  "body.map(x => (x._1, x._2.map((v) => v.a), x._2.map((v) => v.b)))"
//     }
//   }
// }
