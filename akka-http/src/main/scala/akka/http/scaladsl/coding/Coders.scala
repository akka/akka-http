/*
 * Copyright (C) 2020-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.http.scaladsl.model.HttpMessage
import scala.annotation.nowarn

import scala.collection.immutable

@nowarn("msg=in package coding is deprecated")
object Coders {
  def Gzip: Coder = akka.http.scaladsl.coding.Gzip
  def Gzip(
    messageFilter:    HttpMessage => Boolean = Encoder.DefaultFilter,
    compressionLevel: Int                    = GzipCompressor.DefaultCompressionLevel): Coder =
    new Gzip(compressionLevel, messageFilter)

  def Deflate: Coder = akka.http.scaladsl.coding.Deflate
  def Deflate(
    messageFilter:    HttpMessage => Boolean = Encoder.DefaultFilter,
    compressionLevel: Int                    = DeflateCompressor.DefaultCompressionLevel
  ): Coder = new Deflate(compressionLevel, messageFilter)

  def NoCoding: Coder = akka.http.scaladsl.coding.NoCoding

  val DefaultCoders: immutable.Seq[Coder] = immutable.Seq(Gzip, Deflate, NoCoding)
}
