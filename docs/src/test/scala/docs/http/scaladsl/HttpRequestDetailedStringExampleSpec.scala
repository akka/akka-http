/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.model.{ HttpEntity, HttpRequest }
import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
import akka.testkit.AkkaSpec

import scala.collection.immutable

class HttpRequestDetailedStringExampleSpec extends AkkaSpec {

  // Custom string representation which includes headers
  def toDetailedString(request: HttpRequest): String = {
    import request._
    s"""HttpRequest(${_1},${_2},${_3},${_4},${_5})"""
  }

  "Include headers in custom string representation" in {

    // An HTTP header containing Personal Identifying Information
    val piiHeader = Authorization(BasicHttpCredentials("user", "password"))

    // An HTTP entity containing Personal Identifying Information
    val piiBody: HttpEntity.Strict =
      "This body contains information about [user]"

    val httpRequestWithHeadersAndBody =
      HttpRequest(entity = piiBody, headers = immutable.Seq(piiHeader))

    // Our custom string representation includes body and headers string representations...
    assert(
      toDetailedString(httpRequestWithHeadersAndBody)
        .contains(piiHeader.toString)
    )
    assert(
      toDetailedString(httpRequestWithHeadersAndBody).contains(piiBody.toString)
    )

    // ... while default `toString` doesn't.
    assert(!s"$httpRequestWithHeadersAndBody".contains(piiHeader.unsafeToString))
    assert(!s"$httpRequestWithHeadersAndBody".contains(piiBody.data.utf8String))
  }

}
