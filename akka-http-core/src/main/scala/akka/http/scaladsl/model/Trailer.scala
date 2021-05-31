/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.annotation.ApiMayChange
import akka.http.javadsl.{ model => jm }

import scala.collection.immutable

class Trailer(@ApiMayChange val headers: immutable.Seq[(String, String)]) extends jm.Trailer {
  /**
   * Java API
   */
  override def addHeader(header: jm.HttpHeader): Trailer = {
    val sheader = header.asInstanceOf[HttpHeader]
    new Trailer((sheader.name, sheader.value) +: headers)
  }

  /**
   * Java API
   */
  override def addHeaders(headers: Iterable[jm.HttpHeader]): Trailer = headers.foldLeft(this)((acc, h) => acc.addHeader(h))

  /**
   * Java API
   */
  override def withHeaders(headers: Iterable[jm.HttpHeader]): Trailer = Trailer(immutable.Seq.empty).addHeaders(headers)
}
object Trailer {
  def apply(): Trailer = new Trailer(immutable.Seq.empty)
  def apply(header: HttpHeader): Trailer =
    new Trailer(immutable.Seq(header).map(h => (h.name, h.value)))
  def apply(headers: immutable.Seq[HttpHeader]) = new Trailer(immutable.Seq.empty).addHeaders(headers)
}
