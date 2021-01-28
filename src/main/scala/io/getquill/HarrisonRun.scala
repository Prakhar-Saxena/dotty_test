package io.getquill

import io.getquill.quoter.Dsl._
import io.getquill.quoter.Dsl.autoQuote
import io.getquill.parser._

object HarrisonRun {
  def main(args: Array[String]):Unit = {
    /*
    val x : Int = 6
    val y : Int = 5
    */
    case class Person(name:String, age:Int)

    inline def output = quote{query[Person].filter(_.name=="Joe").update(_.name -> "John")}
    // inline def output = quote{query[Person].insert(_.name -> "John", _.age -> 12)}//.filter(_.name=="Joe")
    // inline def output2 = quote { output }
    // println(output2.ast)
    // val output = quote{ x.toLowerCase }//compileeeee
    println(output)
  }
}