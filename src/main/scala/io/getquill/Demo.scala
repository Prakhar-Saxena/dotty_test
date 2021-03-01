package io.getquill

import io.getquill.quoter.Dsl._
import io.getquill.quoter.Dsl.autoQuote
import io.getquill.parser._

object Demo {
  def main(args: Array[String]):Unit = {
    println('\n'*10)

    val ctx = new MirrorContext(MirrorSqlDialect, Literal)
    import ctx._

    case class Person(name:String, age:Int)

    //Query // Prakhar, Adam & Harley
    inline def takeOutput = quote {
        query[Person].filter(p => p.name == "John").take(3)
        //TAKE 3 from Person WHERE name = "John" LIMIT 3
    }
    println()
    ctx.run(takeOutput)

    inline def mapOutput = quote {
        query[Person].filter(p => p.name == "John").map(_.name.toLowerCase)
        //SELECT LOWER (p.name) FROM Person p WHERE p.name = 'John'
    }
    println()
    ctx.run(mapOutput)

    inline def unionOutput = quote {
        query[Person].filter(p => p.name == "John").union(query[Person].filter(p => p.name == "Joe"))
        //SELECT x.name, x.age FROM ((SELECT p.name, p.age FROM Person p WHERE p.name = 'John') UNION (SELECT p1.name, p1.age FROM Person p1 WHERE p1.name = 'Joe')) AS x
    }
    println()
    ctx.run(unionOutput)

    
    //Action // Prakhar
    inline def insertOutput = quote {
      query[Person].insert(_.name -> "John", _.age -> 21)
      //INSERT INTO Person (name,age) VALUES (?, ?)
    }
    println()
    ctx.run(insertOutput)

    inline def updateOutput = quote {
      query[Person].filter(_.name=="Joe").update(_.name -> "John")
       //UPDATE Person SET name = ? WHERE name = ?
    }
    println()
    ctx.run(updateOutput)

    inline def deleteOutput = quote {
      query[Person].filter(p => p.name == "Joe").delete
      //DELTE FROM Person WHERE name = 'Joe'
    }
    println()
    ctx.run(deleteOutput)
    
    //Operations // Harrison
    val five = 5
    inline def numericOperationOutput = quote {
      five + 6;
    }
    println(numericOperationOutput)

    inline def stringOperationOutput = quote {
      "yada".toUpperCase
    }
    println(stringOperationOutput)


    //Options // Jared & Nick
    case class PersonOpt(id: Int, name: Option[String])
    case class Address(street: String, zip: Int, fk: Option[Int])

    inline def isDefined = quote {
      query[PersonOpt].filter(p => p.name.isDefined)
    }
    println(ctx.run(isDefined))

    inline def exists = quote {
      query[PersonOpt]
        .join(query[Address]) // Option.map
        .on((p, a)=> a.fk.exists(_ == p.id)) // Option.exists
    }
    println(ctx.run(exists))

    //compileeeeeeeeeee

    println('\n'*10)
  }
}