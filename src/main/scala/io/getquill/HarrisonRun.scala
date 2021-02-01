package io.getquill

import io.getquill.quoter.Dsl._
import io.getquill.quoter.Dsl.autoQuote
import io.getquill.parser._

object HarrisonRun {
  def main(args: Array[String]):Unit = {
    /*
    val x : Int = 6
    val y : Int = 5
  

    val x : String = "h"
    val y : String = "o"

    val output = quote{ x.toUpperCase }//compileee
    println(output)
    */

    /*
    */
    case class Age(value: Int) extends Embedded  
    case class Person(name: String, age: Age)

    inline def q = quote{ query[Person].insert(Person("Joe", Age(123))) }
    println(q.ast)
    

  }
}