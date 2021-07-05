package akka.http.impl.util

import akka.annotation.InternalApi
import com.github.ghik.silencer.silent

/**
 * INTERNAL API
 */
@InternalApi
private[http] object StringTools {
  @silent("deprecated")
  def asciiStringFromBytes(bytes: Array[Byte]): String =
    // Deprecated constructor but also (unfortunately) the fastest way to convert a ASCII encoded byte array
    // into a String without extra copying.
    new String(bytes, 0)
}
