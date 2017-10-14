/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.concurrent.{ Await, Promise }
import scala.concurrent.duration._
import scala.util.Try
import akka.http.scaladsl.model._
import akka.testkit._
import headers._
import java.net.InetAddress

class MiscDirectivesSpec extends RoutingSpec {

  "the extractClientIP directive" should {
    "extract from a X-Forwarded-For header" in {
      Get() ~> addHeaders(`X-Forwarded-For`(remoteAddress("2.3.4.5")), RawHeader("x-real-ip", "1.2.3.4")) ~> {
        extractClientIP { echoComplete }
      } ~> check { responseAs[String] shouldEqual "2.3.4.5" }
    }
    "extract from a Remote-Address header" in {
      Get() ~> addHeaders(`X-Real-Ip`(remoteAddress("1.2.3.4")), `Remote-Address`(remoteAddress("5.6.7.8"))) ~> {
        extractClientIP { echoComplete }
      } ~> check { responseAs[String] shouldEqual "5.6.7.8" }
    }
    "extract from a X-Real-IP header" in {
      Get() ~> addHeader(`X-Real-Ip`(remoteAddress("1.2.3.4"))) ~> {
        extractClientIP { echoComplete }
      } ~> check { responseAs[String] shouldEqual "1.2.3.4" }
    }
    "extract unknown when no headers" in {
      Get() ~> {
        extractClientIP { echoComplete }
      } ~> check { responseAs[String] shouldEqual "unknown" }
    }
  }

  "the selectPreferredLanguage directive" should {
    "Accept-Language: de, en" test { selectFrom ⇒
      selectFrom("de", "en") shouldEqual "de"
      selectFrom("en", "de") shouldEqual "en"
    }
    "Accept-Language: en, de;q=.5" test { selectFrom ⇒
      selectFrom("de", "en") shouldEqual "en"
      selectFrom("en", "de") shouldEqual "en"
    }
    "Accept-Language: en;q=.5, de" test { selectFrom ⇒
      selectFrom("de", "en") shouldEqual "de"
      selectFrom("en", "de") shouldEqual "de"
    }
    "Accept-Language: en-US, en;q=.7, *;q=.1, de;q=.5" test { selectFrom ⇒
      selectFrom("en", "en-US") shouldEqual "en-US"
      selectFrom("de", "en") shouldEqual "en"
      selectFrom("de", "hu") shouldEqual "de"
      selectFrom("de-DE", "hu") shouldEqual "de-DE"
      selectFrom("hu", "es") shouldEqual "hu"
      selectFrom("es", "hu") shouldEqual "es"
    }
    "Accept-Language: en, *;q=.5, de;q=0" test { selectFrom ⇒
      selectFrom("es", "de") shouldEqual "es"
      selectFrom("de", "es") shouldEqual "es"
      selectFrom("es", "en") shouldEqual "en"
    }
    "Accept-Language: en, *;q=0" test { selectFrom ⇒
      selectFrom("es", "de") shouldEqual "es"
      selectFrom("de", "es") shouldEqual "de"
      selectFrom("es", "en") shouldEqual "en"
    }
  }

  "the withSizeLimit directive" should {
    "not apply if entity is not consumed" in {
      val route = withSizeLimit(500) { completeOk }

      Post("/abc", entityOfSize(500)) ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }

      Post("/abc", entityOfSize(501)) ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "apply if entity is consumed" in {
      val route = withSizeLimit(500) {
        entity(as[String]) { _ ⇒
          completeOk
        }
      }

      Post("/abc", entityOfSize(500)) ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }

      Post("/abc", entityOfSize(501)) ~> Route.seal(route) ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

    "apply if form data is fully consumed into a map" in {
      val route =
        withSizeLimit(64) {
          formFieldMap { _ ⇒
            completeOk
          }
        }

      Post("/abc", formDataOfSize(32)) ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }

      Post("/abc", formDataOfSize(128)) ~> Route.seal(route) ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual "The request content was malformed:\n" +
          "EntityStreamSizeException: actual entity size (Some(134)) " +
          "exceeded content length limit (64 bytes)! " +
          "You can configure this by setting `akka.http.[server|client].parsing.max-content-length` " +
          "or calling `HttpEntity.withSizeLimit` before materializing the dataBytes stream."
      }
    }

    "properly handle nested directives by applying innermost `withSizeLimit` directive" in {
      val route =
        withSizeLimit(500) {
          withSizeLimit(800) {
            entity(as[String]) { _ ⇒
              completeOk
            }
          }
        }

      Post("/abc", entityOfSize(800)) ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }

      Post("/abc", entityOfSize(801)) ~> Route.seal(route) ~> check {
        status shouldEqual StatusCodes.BadRequest
      }

      val route2 =
        withSizeLimit(500) {
          withSizeLimit(400) {
            entity(as[String]) { _ ⇒
              completeOk
            }
          }
        }

      Post("/abc", entityOfSize(400)) ~> route2 ~> check {
        status shouldEqual StatusCodes.OK
      }

      Post("/abc", entityOfSize(401)) ~> Route.seal(route2) ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  "the withoutSizeLimit directive" should {
    "skip request entity size verification" in {
      val route =
        withSizeLimit(500) {
          withoutSizeLimit {
            entity(as[String]) { _ ⇒
              completeOk
            }
          }
        }

      Post("/abc", entityOfSize(501)) ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }
  }

  implicit class AddStringToIn(acceptLanguageHeaderString: String) {
    def test(body: ((String*) ⇒ String) ⇒ Unit): Unit =
      s"properly handle `$acceptLanguageHeaderString`" in {
        val Array(name, value) = acceptLanguageHeaderString.split(':')
        val acceptLanguageHeader = HttpHeader.parse(name.trim, value) match {
          case HttpHeader.ParsingResult.Ok(h: `Accept-Language`, Nil) ⇒ h
          case result ⇒ fail(result.toString)
        }
        body { availableLangs ⇒
          val selected = Promise[String]()
          val first = Language(availableLangs.head)
          val more = availableLangs.tail.map(Language(_))
          Get() ~> addHeader(acceptLanguageHeader) ~> {
            selectPreferredLanguage(first, more: _*) { lang ⇒
              complete(lang.toString)
            }
          } ~> check(selected.complete(Try(responseAs[String])))
          Await.result(selected.future, 1.second.dilated)
        }
      }
  }

  def remoteAddress(ip: String) = RemoteAddress(InetAddress.getByName(ip))

  private def entityOfSize(size: Int) = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "0" * size)

  private def formDataOfSize(size: Int) = FormData(Map("field" → ("0" * size)))
}
