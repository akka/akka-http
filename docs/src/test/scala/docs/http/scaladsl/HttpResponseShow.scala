/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.model.{ HttpEntity, HttpResponse }
import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
import akka.testkit.AkkaSpec

import scala.collection.immutable

class HttpResponseShow extends AkkaSpec {

  // An HTTP header containing Personal Identifying Information
  val piiHeader = Authorization(BasicHttpCredentials("user", "password"))

  // An HTTP entity containing Personal Identifying Information
  val piiBody: HttpEntity.Strict = "This body contains information about [user]"

  val httpResponseWithHeadersAndBody = HttpResponse(entity = piiBody, headers = immutable.Seq(piiHeader))

  "Include headers in string representation using Typelevel's Cats" in {
    import cats.Show
    import cats.syntax.show._

    implicit val showHttpResponseInstance = Show.show { response: HttpResponse =>
      import response._
      // This string representation includes headers!
      s"""HttpResponse(${_1},${_2},${_3},${_4})"""
    }

    // Our custom string representation includes body and headers string representations...
    assert(show"$httpResponseWithHeadersAndBody".contains(piiHeader.toString))
    assert(show"$httpResponseWithHeadersAndBody".contains(piiBody.toString))

    // ... while default `toString` doesn't.
    assert(!s"$httpResponseWithHeadersAndBody".contains(piiHeader.toString))
    assert(!s"$httpResponseWithHeadersAndBody".contains(piiBody.toString))
  }

  "Include headers in string representation using Scalaz" in {
    import scalaz.Show
    import scalaz.syntax.show._

    implicit val showHttpResponseInstance = Show.shows { response: HttpResponse =>
      import response._
      // This string representation includes headers!
      s"""HttpResponse(${_1},${_2},${_3},${_4})"""
    }

    // Our custom string representation includes body and headers string representations...
    assert(httpResponseWithHeadersAndBody.shows.contains(piiHeader.toString))
    assert(httpResponseWithHeadersAndBody.shows.contains(piiBody.toString))

    // ... while default `toString` doesn't.
    assert(!s"$httpResponseWithHeadersAndBody".contains(piiHeader.toString))
    assert(!s"$httpResponseWithHeadersAndBody".contains(piiBody.toString))
  }

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

    implicit val showHttpResponseInstance = Show.show { response: HttpResponse =>
      import response._
      // This string representation includes headers!
      s"""HttpResponse(${_1},${_2},${_3},${_4})"""
    }

    // Our custom string representation includes body and headers string representations...
    assert(httpResponseWithHeadersAndBody.show.contains(piiHeader.toString))
    assert(httpResponseWithHeadersAndBody.show.contains(piiBody.toString))

    // ... while default `toString` doesn't.
    assert(!s"$httpResponseWithHeadersAndBody".contains(piiHeader.toString))
    assert(!s"$httpResponseWithHeadersAndBody".contains(piiBody.toString))
  }

  "Include headers in string representation using an implicit extension class" in {

    implicit class HttpResponseWithShow(response: HttpResponse) {
      import response._
      def show: String = s"""HttpResponse(${_1},${_2},${_3},${_4})"""
    }

    // Our custom string representation includes body and headers string representations...
    assert(httpResponseWithHeadersAndBody.show.contains(piiHeader.toString))
    assert(httpResponseWithHeadersAndBody.show.contains(piiBody.toString))

    // ... while default `toString` doesn't.
    assert(!s"$httpResponseWithHeadersAndBody".contains(piiHeader.toString))
    assert(!s"$httpResponseWithHeadersAndBody".contains(piiBody.toString))
  }

}
