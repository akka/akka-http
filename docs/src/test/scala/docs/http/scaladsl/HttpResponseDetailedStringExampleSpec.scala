/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.model.{ HttpEntity, HttpResponse }
import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
import akka.testkit.AkkaSpec

import scala.collection.immutable

class HttpResponseDetailedStringExampleSpec extends AkkaSpec {

  // Custom string representation which includes headers
  def toDetailedString(response: HttpResponse): String = {
    import response._
    s"""HttpResponse(${_1},${_2},${_3},${_4})"""
  }

  "Include headers in custom string representation" in {

    // An HTTP header containing Personal Identifying Information
    val piiHeader = Authorization(BasicHttpCredentials("user", "password"))

    // An HTTP entity containing Personal Identifying Information
    val piiBody: HttpEntity.Strict =
      "This body contains information about [user]"

    val httpResponseWithHeadersAndBody =
      HttpResponse(entity = piiBody, headers = immutable.Seq(piiHeader))

    // Our custom string representation includes body and headers string representations...
    assert(
      toDetailedString(httpResponseWithHeadersAndBody)
        .contains(piiHeader.toString)
    )
    assert(
      toDetailedString(httpResponseWithHeadersAndBody)
        .contains(piiBody.toString)
    )

    // ... while default `toString` doesn't.
    assert(!s"$httpResponseWithHeadersAndBody".contains(piiHeader.unsafeToString))
    assert(!s"$httpResponseWithHeadersAndBody".contains(piiBody.data.utf8String))
  }

}
