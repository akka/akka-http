/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.http.scaladsl.model.HttpMessage
import com.github.ghik.silencer.silent

import scala.collection.immutable

@silent("in package coding is deprecated")
object Coders {
  def Gzip: Coder = akka.http.scaladsl.coding.Gzip
  def Gzip(messageFilter: HttpMessage => Boolean): Coder = new Gzip(messageFilter)

  def Deflate: Coder = akka.http.scaladsl.coding.Gzip
  def Deflate(messageFilter: HttpMessage => Boolean): Coder = new Deflate(messageFilter)

  def NoCoding: Coder = akka.http.scaladsl.coding.NoCoding

  val DefaultCoders: immutable.Seq[Coder] = immutable.Seq(Gzip, Deflate, NoCoding)
}
