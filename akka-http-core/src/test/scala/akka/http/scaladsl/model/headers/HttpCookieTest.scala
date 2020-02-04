/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import akka.http.javadsl.{ model => jm }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class HttpCookieTest extends AnyFreeSpec with Matchers {
  "HttpCookies" - {
    "SameSite parse" - {
      "Strict" in {
        SameSite("Strict") must be(Some(SameSite.Strict))
      }
      "Lax" in {
        SameSite("Lax") must be(Some(SameSite.Lax))
      }
      "None" in {
        SameSite("None") must be(Some(SameSite.None))
      }
      "return Option empty when value is invalid" in {
        SameSite("Wrong") must be(None)
      }
      "be case insensitive" in {
        SameSite("sTRIct") must be(Some(SameSite.Strict))
        SameSite("lAX") must be(Some(SameSite.Lax))
        SameSite("NONE") must be(Some(SameSite.None))
      }
    }
    "SameSite conversion to Java" - {
      "convert Strict correctly" in {
        SameSite.Strict.asJava must be(jm.headers.SameSite.Strict)
      }
      "convert Lax correctly" in {
        SameSite.Lax.asJava must be(jm.headers.SameSite.Lax)
      }
      "convert None correctly" in {
        SameSite.None.asJava must be(jm.headers.SameSite.None)
      }
    }
  }
}
