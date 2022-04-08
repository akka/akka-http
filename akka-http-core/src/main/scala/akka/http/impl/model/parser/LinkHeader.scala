/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.model.parser

import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.immutable.TreeMap

import akka.parboiled2.Parser
import akka.http.scaladsl.model.{ ParsingException, IllegalUriException }
import akka.http.scaladsl.model.headers._

private[parser] trait LinkHeader { this: Parser with CommonRules with CommonActions with StringBuilding =>
  import CharacterClasses._

  // http://tools.ietf.org/html/rfc5988#section-5
  def `link` = rule {
    zeroOrMore(`link-value`).separatedBy(listSep) ~ EOI ~> (Link(_))
  }

  def `link-value` = rule {
    ws('<') ~ UriReference('>') ~ ws('>') ~ oneOrMore(ws(';') ~ `link-param`) ~> (sanitize(_)) ~> (LinkValue(_, _))
  }

  def `link-param` = rule(
    ws("rel") ~ ws('=') ~ `relation-types` ~> LinkParams.rel.apply _
      | ws("anchor") ~ ws('=') ~ ws('"') ~ UriReference('"') ~ ws('"') ~> LinkParams.anchor.apply _
      | ws("rev") ~ ws('=') ~ `relation-types` ~> LinkParams.rev.apply _
      | ws("hreflang") ~ ws('=') ~ language ~> LinkParams.hreflang.apply _
      | ws("media") ~ ws('=') ~ word ~> LinkParams.media.apply _
      | ws("title") ~ ws('=') ~ word ~> LinkParams.title.apply _
      | ws("title*") ~ ws('=') ~ word ~> LinkParams.`title*`.apply _ // support full `ext-value` notation from http://tools.ietf.org/html/rfc5987#section-3.2.1
      | (ws("type") ~ ws('=') ~ (ws('"') ~ `link-media-type` ~ ws('"') | `link-media-type`) ~> LinkParams.`type`.apply _))
  // TODO: support `link-extension`

  def `relation-types` = rule(
    ws('"') ~ oneOrMore(`relation-type`).separatedBy(oneOrMore(SP)) ~> (_.mkString(" ")) ~ ws('"')
      | `relation-type` ~ OWS)

  def `relation-type` = rule { `reg-rel-type` | `ext-rel-type` }

  def `reg-rel-type` = rule {
    capture(LOWER_ALPHA ~ zeroOrMore(`reg-rel-type-octet`)) ~ !VCHAR
  }

  def `ext-rel-type` = rule {
    URI
  }

  ////////////////////////////// helpers ///////////////////////////////////

  def UriReference(terminationChar: Char) = rule {
    capture(oneOrMore(!terminationChar ~ VCHAR)) ~> (newUriParser(_).parseUriReference())
  }

  def URI = rule {
    capture(oneOrMore(!'"' ~ !';' ~ !',' ~ VCHAR)) ~> { s =>
      try new UriParser(s).parseUriReference()
      catch {
        case IllegalUriException(info) => throw ParsingException(info.withSummaryPrepended("Illegal `Link` header relation-type"))
      }
      s
    }
  }

  def `link-media-type` = rule { `media-type` ~> ((mt, st, pm) => getMediaType(mt, st, pm contains "charset", TreeMap(pm: _*))) }

  // filter out subsequent `rel`, `media`, `title`, `type` and `type*` params
  @tailrec private def sanitize(params: Seq[LinkParam], result: immutable.Seq[LinkParam] = Nil, seenRel: Boolean = false,
                                seenMedia: Boolean = false, seenTitle: Boolean = false, seenTitleS: Boolean = false,
                                seenType: Boolean = false): immutable.Seq[LinkParam] =
    params match {
      case Seq((x: LinkParams.rel), tail @ _*)      => sanitize(tail, if (seenRel) result else result :+ x, seenRel = true, seenMedia, seenTitle, seenTitleS, seenType)
      case Seq((x: LinkParams.media), tail @ _*)    => sanitize(tail, if (seenMedia) result else result :+ x, seenRel, seenMedia = true, seenTitle, seenTitleS, seenType)
      case Seq((x: LinkParams.title), tail @ _*)    => sanitize(tail, if (seenTitle) result else result :+ x, seenRel, seenMedia, seenTitle = true, seenTitleS, seenType)
      case Seq((x: LinkParams.`title*`), tail @ _*) => sanitize(tail, if (seenTitleS) result else result :+ x, seenRel, seenMedia, seenTitle, seenTitleS = true, seenType)
      case Seq((x: LinkParams.`type`), tail @ _*)   => sanitize(tail, if (seenType) result else result :+ x, seenRel, seenMedia, seenTitle, seenTitleS, seenType = true)
      case Seq(head, tail @ _*)                     => sanitize(tail, result :+ head, seenRel, seenMedia, seenTitle, seenTitleS, seenType)
      case Nil                                      => result
    }
}
