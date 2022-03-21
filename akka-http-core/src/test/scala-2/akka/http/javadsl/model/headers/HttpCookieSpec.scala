/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers

import java.util.{ Optional, OptionalLong }

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import akka.http.scaladsl.model.{ headers => sh }

class HttpCookieSpec extends AnyFreeSpec with Matchers {

  "The Java HttpCookie API" - {
    "create cookies with" - {
      "name and value" in {
        val cookie = HttpCookie.create("cookieName", "cookieValue")
        cookie.name() must be("cookieName")
        cookie.value() must be("cookieValue")
      }
      "SameSite None by default" in {
        HttpCookie.create("cookieName", "cookieValue").getSameSite must be(Optional.empty)
      }
      "SameSite as specified" in {
        HttpCookie.create(
          "cookeName",
          "cookieValue",
          Optional.empty(), // expires
          OptionalLong.empty(), // maxAge
          Optional.empty(), // domain
          Optional.empty(), // path
          true, // secure
          true, // httpOnly
          Optional.empty(), // extension
          Optional.of(SameSite.Lax)
        ).getSameSite must be(Optional.of(SameSite.Lax))
      }
    }
    "SameSite conversation to Scala" - {
      "convert Strict" in {
        SameSite.Strict.asScala() must be(sh.SameSite.Strict)
      }
      "convert Lax" in {
        SameSite.Lax.asScala() must be(sh.SameSite.Lax)
      }
      "convert None" in {
        SameSite.None.asScala() must be(sh.SameSite.None)
      }
    }
  }
}
