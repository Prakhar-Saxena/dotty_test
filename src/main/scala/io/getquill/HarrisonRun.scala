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

    val x = Set[Int]()
    val y : String = "o"

    val output = quote{ x.nonEmpty }//compileeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
    println(output)

  }
}