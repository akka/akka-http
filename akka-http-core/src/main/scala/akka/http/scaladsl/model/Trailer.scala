/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.http.javadsl.{ model => jm }

import scala.collection.immutable

case class Trailer(headers: immutable.Seq[HttpHeader]) extends jm.Trailer {
  /**
   * Java API
   */
  override def getHeaders(): Iterable[HttpHeader] = headers

  /**
   * Java API
   */
  override def addHeader(header: jm.HttpHeader): Trailer = new Trailer(header.asInstanceOf[HttpHeader] +: headers)

  /**
   * Java API
   */
  override def addHeaders(headers: Iterable[jm.HttpHeader]): Trailer = ???

  /**
   * Java API
   */
  override def withHeaders(headers: Iterable[jm.HttpHeader]): Trailer = ???
}
object Trailer {
  def apply(header: HttpHeader): Trailer = Trailer(immutable.Seq(header))
}
