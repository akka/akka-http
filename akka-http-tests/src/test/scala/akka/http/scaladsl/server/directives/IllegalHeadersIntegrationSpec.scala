/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{ Accept, RawHeader }
import akka.http.scaladsl.server.RoutingSpec

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Has to exercise the entire stack, thus uses ~!> (not reproducable using just ~>).
 */
class IllegalHeadersIntegrationSpec extends RoutingSpec {

  "Illegal content type in request" should {
    val route = extractRequest { req ⇒
      complete(s"Accept:${req.header[Accept]}, byName:${req.headers.find(_.is("accept"))}")
    }

    // see: https://github.com/akka/akka-http/issues/1072
    "not StackOverflow but be rejected properly" in {
      val theIllegalHeader = RawHeader("Accept", "*/xml")
      Get().addHeader(theIllegalHeader) ~!> route ~> check {
        status should ===(StatusCodes.OK)
        responseAs[String] shouldEqual "Accept:None, byName:Some(accept: */xml)"
      }
    }
  }

}
