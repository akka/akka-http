/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.model.parser

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap

import akka.parboiled2.Parser
import akka.http.scaladsl.model._

private[parser] trait ContentTypeHeader { this: Parser with CommonRules with CommonActions with StringBuilding =>

  // http://tools.ietf.org/html/rfc7231#section-3.1.1.5
  def `content-type` = rule {
    `media-type` ~ EOI ~> ((main, sub, params) => headers.`Content-Type`(contentType(main, sub, params)))
  }

  @tailrec private def contentType(
    main:    String,
    sub:     String,
    params:  Seq[(String, String)],
    charset: Option[HttpCharset]   = None,
    builder: StringMapBuilder      = null): ContentType =
    params match {
      case Nil =>
        val parameters = if (builder eq null) Map.empty[String, String] else builder.result()
        getMediaType(main, sub, charset.isDefined, parameters) match {
          case x: MediaType.Binary => ContentType.Binary(x)
          case x: MediaType.WithFixedCharset => ContentType.WithFixedCharset(x)
          case x: MediaType.WithOpenCharset if charset.isDefined => ContentType.WithCharset(x, charset.get)
          case x: MediaType.WithOpenCharset if charset.isEmpty => ContentType.WithMissingCharset(x)
          case _ => throw new IllegalStateException("Unexpected media type value") // compiler completeness check pleaser
        }

      case Seq((key, value), tail @ _*) if equalsAsciiCaseInsensitive(key, "charset") =>
        contentType(main, sub, tail, Some(getCharset(value)), builder)

      case Seq(kvp, tail @ _*) =>
        val b = if (builder eq null) TreeMap.newBuilder[String, String] else builder
        b += kvp
        contentType(main, sub, tail, charset, b)
      case _ => throw new IllegalStateException("Unexpected params") // compiler completeness check pleaser
    }
}
