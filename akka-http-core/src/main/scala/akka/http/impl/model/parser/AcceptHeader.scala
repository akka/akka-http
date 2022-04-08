/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.model.parser

import scala.collection.immutable.TreeMap

import akka.parboiled2.Parser
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ MediaRange, MediaRanges }
import akka.http.impl.util._

private[parser] trait AcceptHeader { this: Parser with CommonRules with CommonActions with StringBuilding =>
  import CharacterClasses._

  // http://tools.ietf.org/html/rfc7231#section-5.3.2
  def accept = rule {
    zeroOrMore(`media-range-decl`).separatedBy(listSep) ~ EOI ~> (Accept(_))
  }

  def `media-range-decl` = rule {
    `media-range-def` ~ OWS ~ zeroOrMore(ws(';') ~ parameter) ~> { (main, sub, params) =>
      if (sub == "*") {
        val mainLower = main.toRootLowerCase
        MediaRanges.getForKey(mainLower) match {
          case Some(registered) => if (params.isEmpty) registered else registered.withParams(TreeMap(params: _*))
          case None             => MediaRange.custom(mainLower, TreeMap(params: _*))
        }
      } else {
        val (p, q) = MediaRange.splitOffQValue(TreeMap(params: _*))
        MediaRange(getMediaType(main, sub, p contains "charset", p), q)
      }
    }
  }

  // this specific ordering PREVENTS that next rule is allowed to parse `*/xyz` as a valid media range
  def `media-range-def` = rule {
    "*/*" ~ push("*") ~ push("*") |
      '*' ~ push("*") ~ push("*") |
      `type` ~ '/' ~ ('*' ~ !tchar ~ push("*") | subtype)
  }
}

