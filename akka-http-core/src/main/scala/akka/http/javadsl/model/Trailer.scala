/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model

import akka.http.scaladsl.{ model => sm }

import scala.collection.immutable

/** Trailing headers for HTTP/2 responses */
trait Trailer {
  def getHeaders(): Iterable[HttpHeader]

  /**
   * Returns a copy of this trailer with the given header added to the list of headers.
   */
  def addHeader(header: HttpHeader): Trailer

  /**
   * Returns a copy of this trailer with the given headers added to the list of headers.
   */
  def addHeaders(headers: Iterable[HttpHeader]): Trailer

  /**
   * Returns a copy of this trailer with new headers.
   */
  def withHeaders(headers: Iterable[HttpHeader]): Trailer
}
object Trailer {
  def create(): Trailer = new sm.Trailer(immutable.Seq.empty)
}
