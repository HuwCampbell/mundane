package com.ambiata.mundane
package parse

import org.specs2._
import Delimited._
import org.specs2.matcher.ThrownExpectations

class DelimitedSpec extends Specification with ThrownExpectations { def is = s2"""

 Given a string, can split it by:
   pipe  $pipe
   comma $comma
   tab   $tab

 If a field contains a separator, this field can be protected with quotes $escaped
   empty field $emptyField

 Delimited parsing must be fast enough to parse 2M lines in a reasonable time $performance
                                                                          """

  def pipe = {
    parsePsv("a|b|c") must_== List("a", "b", "c")
    parsePsv("a|b|c|") must_== List("a", "b", "c", "")
  }

  def comma = {
    parseCsv("a,b,c") must_== List("a", "b", "c")
    parseCsv("a,b,c,") must_== List("a", "b", "c", "")
  }

  def tab = {
    parseTsv("a\tb\tc") must_== List("a", "b", "c")
    parseTsv("a\tb\tc\t") must_== List("a", "b", "c", "")
  }

  def escaped = {
    parseCsv("1") === List("1")
    parseCsv(" this is ") === List(" this is ")
    parseCsv(""" "this, is, my string" """) === List("this, is, my string")
    parseCsv(""" "this, "", my string" """) === List("""this, ", my string""")
  }

  def emptyField = {
    // empty quotes
    val empty = ""
    parseCsv(s"$empty") === List("")
    parseCsv("""1693206,1,"", ,3""") === List("1693206","1", "", " ", "3")
  }

  def performance = {
    val n = 2000
    (1 to n).map(i => i.toString+"|"+i.toString).foreach(parsePsv)
    ok
  }

}