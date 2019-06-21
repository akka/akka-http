/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.model.{ HttpEntity, HttpRequest }
import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
import akka.testkit.AkkaSpec

import scala.collection.immutable

class HttpRequestShow extends AkkaSpec {

  // An HTTP header containing Personal Identifying Information
  val piiHeader = Authorization(BasicHttpCredentials("user", "password"))

  // An HTTP entity containing Personal Identifying Information
  val piiBody: HttpEntity.Strict = "This body contains information about [user]"

  val httpRequestWithHeadersAndBody = HttpRequest(entity = piiBody, headers = immutable.Seq(piiHeader))

  "Include headers in string representation using custom Show typeclass" in {
    trait Show[T] {
      def show(t: T): String
    }

    object Show {
      def show[T](f: T => String): Show[T] = new Show[T] {
        override def show(t: T): String = f(t)
      }

      implicit class ShowOps[T: Show](x: T) {
        def show: String = implicitly[Show[T]].show(x)
      }
    }

    import Show.ShowOps

    implicit val showHttpRequestInstance = Show.show { request: HttpRequest =>
      import request._
      // This string representation includes headers!
      s"""HttpRequest(${_1},${_2},${_3},${_4},${_5})"""
    }

    // Our custom string representation includes body and headers string representations...
    assert(httpRequestWithHeadersAndBody.show.contains(piiHeader.toString))
    assert(httpRequestWithHeadersAndBody.show.contains(piiBody.toString))

    // ... while default `toString` doesn't.
    assert(!s"$httpRequestWithHeadersAndBody".contains(piiHeader.toString))
    assert(!s"$httpRequestWithHeadersAndBody".contains(piiBody.toString))
  }

  "Include headers in string representation using an implicit extension class" in {

    implicit class HttpRequestWithShow(request: HttpRequest) {
      import request._
      def show: String = s"""HttpRequest(${_1},${_2},${_3},${_4},${_5})"""
    }

    // Our custom string representation includes body and headers string representations...
    assert(httpRequestWithHeadersAndBody.show.contains(piiHeader.toString))
    assert(httpRequestWithHeadersAndBody.show.contains(piiBody.toString))

    // ... while default `toString` doesn't.
    assert(!s"$httpRequestWithHeadersAndBody".contains(piiHeader.toString))
    assert(!s"$httpRequestWithHeadersAndBody".contains(piiBody.toString))
  }

}
