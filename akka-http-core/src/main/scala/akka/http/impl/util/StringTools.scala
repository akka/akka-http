/*
 * Copyright (C) 2021-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import akka.annotation.InternalApi
import akka.util.Unsafe
import scala.annotation.nowarn

/**
 * INTERNAL API
 */
@InternalApi
private[http] object StringTools {
  @nowarn("msg=deprecated")
  def asciiStringFromBytes(bytes: Array[Byte]): String =
    // Deprecated constructor but also (unfortunately) the fastest way to convert a ASCII encoded byte array
    // into a String without extra copying.
    new String(bytes, 0)

  def asciiStringBytes(string: String): Array[Byte] = {
    val bytes = new Array[Byte](string.length)
    Unsafe.copyUSAsciiStrToBytes(string, bytes)
    bytes
  }
}
