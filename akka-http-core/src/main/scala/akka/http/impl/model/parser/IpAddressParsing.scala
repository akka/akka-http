/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.parboiled2._
import akka.parboiled2.support.hlist.{ ::, HNil }

private[parser] trait IpAddressParsing { this: Parser =>
  import CharacterClasses._

  def `ip-v4-address`: Rule[HNil, Array[Byte] :: HNil] = rule[HNil, Array[Byte] :: HNil] {
    `ip-number` ~ '.' ~ `ip-number` ~ '.' ~ `ip-number` ~ '.' ~ `ip-number` ~> (Array[Byte](_: Byte, _: Byte, _: Byte, _: Byte))
  }

  def `ip-number` = rule {
    capture(
      '2' ~ (DIGIT04 ~ DIGIT | '5' ~ DIGIT05)
        | '1' ~ DIGIT ~ DIGIT
        | DIGIT19 ~ DIGIT
        | DIGIT) ~> (java.lang.Integer.parseInt(_).toByte)
  }

  def `ip-v6-address`: Rule1[Array[Byte]] = {
    import CharUtils.{ hexValue => hv }
    var a: Array[Byte] = null
    def zero(ix: Int): Rule[HNil, HNil] = rule { run(a(ix)= 0.toByte) }
    def zero2(ix: Int): Rule[HNil, HNil] = rule { run { a(ix) = 0.toByte; a(ix + 1) = 0.toByte; } }
    def h4(ix: Int): Rule[HNil, HNil] = rule { HEXDIG ~ run(a(ix)= hv(lastChar).toByte) }
    def h8(ix: Int): Rule[HNil, HNil] = rule { HEXDIG ~ HEXDIG ~ run(a(ix)= (hv(charAt(-2)) * 16 + hv(lastChar)).toByte) }
    def h16(ix: Int): Rule[HNil, HNil] = rule { h8(ix) ~ h8(ix + 1) | h4(ix) ~ h8(ix + 1) | zero(ix) ~ h8(ix + 1) | zero(ix) ~ h4(ix + 1) }
    def h16c(ix: Int): Rule[HNil, HNil] = rule { h16(ix) ~ ':' ~ !':' }
    def ch16o(ix: Int): Rule[HNil, HNil] = rule { optional(':' ~ !':') ~ (h16(ix) | zero2(ix)) }
    def ls32: Rule[HNil, HNil] = rule { h16(12) ~ ':' ~ h16(14) | `ip-v4-address` ~> (System.arraycopy(_, 0, a, 12, 4)) }
    def cc(ix: Int): Rule[HNil, HNil] = rule { ':' ~ ':' ~ zero2(ix) }
    def tail2: Rule[HNil, HNil] = rule { h16c(2) ~ tail4 }
    def tail4: Rule[HNil, HNil] = rule { h16c(4) ~ tail6 }
    def tail6: Rule[HNil, HNil] = rule { h16c(6) ~ tail8 }
    def tail8: Rule[HNil, HNil] = rule { h16c(8) ~ tail10 }
    def tail10: Rule[HNil, HNil] = rule { h16c(10) ~ ls32 }
    rule {
      !(':' ~ HEXDIG) ~ push { a = new Array[Byte](16); a } ~ (
        h16c(0) ~ tail2
        | cc(0) ~ tail2
        | ch16o(0) ~ (
          cc(2) ~ tail4
          | ch16o(2) ~ (
            cc(4) ~ tail6
            | ch16o(4) ~ (
              cc(6) ~ tail8
              | ch16o(6) ~ (
                cc(8) ~ tail10
                | ch16o(8) ~ (
                  cc(10) ~ ls32
                  | ch16o(10) ~ (
                    cc(12) ~ h16(14)
                    | ch16o(12) ~ cc(14))))))))
    }
  }

  def `ip-v6-reference`: Rule1[String] = rule { capture('[' ~ oneOrMore(HEXDIG | anyOf(":.")) ~ ']') }
}

