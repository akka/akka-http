/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller.HexInt
import akka.http.scaladsl.model._
import MediaTypes._
import org.scalatest.{ FreeSpec, Inside }

class AnyParamDirectivesSpec extends RoutingSpec with Inside {
  // FIXME: unfortunately, it has make a come back, this time it's reproducible ...
  import akka.http.scaladsl.server.directives.AnyParamDirectives.AnyParamMagnet

  implicit val nodeSeqUnmarshaller =
    ScalaXmlSupport.nodeSeqUnmarshaller(`text/xml`, `text/html`, `text/plain`)

  val urlEncodedForm = FormData(Map("firstName" → "Mike", "age" → "42"))
  val urlEncodedFormWithVip = FormData(Map("firstName" → "Mike", "age" → "42", "VIP" → "true", "super" → "<b>no</b>"))
  val multipartForm = Multipart.FormData {
    Map(
      "firstName" → HttpEntity("Mike"),
      "age" → HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<int>42</int>"),
      "VIPBoolean" → HttpEntity("true"))
  }
  val multipartFormWithTextHtml = Multipart.FormData {
    Map(
      "firstName" → HttpEntity("Mike"),
      "age" → HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<int>42</int>"),
      "VIP" → HttpEntity(ContentTypes.`text/html(UTF-8)`, "<b>yes</b>"),
      "super" → HttpEntity("no"))
  }
  val multipartFormWithFile = Multipart.FormData(
    Multipart.FormData.BodyPart.Strict("file", HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<int>42</int>"),
      Map("filename" → "age.xml")))

  "The 'anyParams' extraction directive" should {
    "properly extract the value of www-urlencoded form fields" in {
      Post("/", urlEncodedForm) ~> {
        anyParams('firstName, "age".as[Int], 'sex.?, "VIP" ? false) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] shouldEqual "Mike42Nonefalse" }
    }
    "properly extract the value of www-urlencoded form fields when an explicit unmarshaller is given" in {
      Post("/", urlEncodedForm) ~> {
        anyParams('firstName, "age".as(HexInt), 'sex.?, "VIP" ? false) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { responseAs[String] shouldEqual "Mike66Nonefalse" }
    }
    "reject the request with a MissingAnyParamRejection if a required form field is missing" in {
      Post("/", urlEncodedForm) ~> {
        anyParams('firstName, "age", 'sex, "VIP" ? false) { (firstName, age, sex, vip) ⇒
          complete(firstName + age + sex + vip)
        }
      } ~> check { rejection shouldEqual MissingAnyParamRejection("sex") }
    }
    "properly extract the value if only a urlencoded deserializer is available for a multipart field that comes without a" +
      "Content-Type (or text/plain)" in {
        Post("/", multipartForm) ~> {
          anyParams('firstName, "age", 'sex.?, "VIPBoolean" ? false) { (firstName, age, sex, vip) ⇒
            complete(firstName + age + sex + vip)
          }
        } ~> check {
          responseAs[String] shouldEqual "Mike<int>42</int>Nonetrue"
        }
      }
    "work even if only a FromStringUnmarshaller is available for a multipart field with custom Content-Type" in {
      Post("/", multipartFormWithTextHtml) ~> {
        anyParams(('firstName, "age", 'super ? false)) { (firstName, age, vip) ⇒
          complete(firstName + age + vip)
        }
      } ~> check {
        responseAs[String] shouldEqual "Mike<int>42</int>false"
      }
    }
  }
  "The 'anyParam' requirement directive" should {
    "block requests that do not contain the required anyParam" in {
      Post("/", urlEncodedForm) ~> {
        anyParam('name ! "Mr. Mike") { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "block requests that contain the required parameter but with an unmatching value" in {
      Post("/", urlEncodedForm) ~> {
        anyParam('firstName ! "Pete") { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "let requests pass that contain the required parameter with its required value" in {
      Post("/", urlEncodedForm) ~> {
        anyParam('firstName ! "Mike") { completeOk }
      } ~> check { response shouldEqual Ok }
    }
  }

  "The 'anyParam' requirement with explicit unmarshaller directive" should {
    "block requests that do not contain the required anyParam" in {
      Post("/", urlEncodedForm) ~> {
        anyParam('oldAge.as(HexInt) ! 78) { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "block requests that contain the required parameter but with an unmatching value" in {
      Post("/", urlEncodedForm) ~> {
        anyParam('age.as(HexInt) ! 78) { completeOk }
      } ~> check { handled shouldEqual false }
    }
    "let requests pass that contain the required parameter with its required value" in {
      Post("/", urlEncodedForm) ~> {
        anyParam('age.as(HexInt) ! 66 /* hex! */ ) { completeOk }
      } ~> check { response shouldEqual Ok }
    }
  }

  "The 'anyParam' repeated directive" should {
    "extract an empty Iterable when the parameter is absent" in {
      Post("/", FormData("age" → "42")) ~> {
        anyParam('hobby.*) { echoComplete }
      } ~> check { responseAs[String] === "List()" }
    }
    "extract all occurrences into an Iterable when parameter is present" in {
      Post("/", FormData("age" → "42", "hobby" → "cooking", "hobby" → "reading")) ~> {
        anyParam('hobby.*) { echoComplete }
      } ~> check { responseAs[String] === "List(cooking, reading)" }
    }
    "extract as Iterable[Int]" in {
      Post("/", FormData("age" → "42", "number" → "3", "number" → "5")) ~> {
        anyParam('number.as[Int].*) { echoComplete }
      } ~> check { responseAs[String] === "List(3, 5)" }
    }
    "extract as Iterable[Int] with an explicit deserializer" in {
      Post("/", FormData("age" → "42", "number" → "3", "number" → "A")) ~> {
        anyParam('number.as(HexInt).*) { echoComplete }
      } ~> check { responseAs[String] === "List(3, 10)" }
    }
  }

  "The 'anyParamMap' directive" should {
    "extract fields with different keys" in {
      Post("/", FormData("age" → "42", "numberA" → "3", "numberB" → "5")) ~> {
        anyParamMap { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Map(age -> 42, numberA -> 3, numberB -> 5)" }
    }
  }

  "The 'anyParamseq' directive" should {
    "extract all fields" in {
      Post("/", FormData("age" → "42", "number" → "3", "number" → "5")) ~> {
        anyParamSeq { echoComplete }
      } ~> check { responseAs[String] shouldEqual "List((age,42), (number,3), (number,5))" }
    }
    "produce empty Seq when FormData is empty" in {
      Post("/", FormData.Empty) ~> {
        anyParamSeq { echoComplete }
      } ~> check { responseAs[String] shouldEqual "List()" }
    }
  }

  "The 'anyParamMultiMap' directive" should {
    "extract fields with different keys (with duplicates)" in {
      Post("/", FormData("age" → "42", "number" → "3", "number" → "5")) ~> {
        anyParamMultiMap { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Map(age -> List(42), number -> List(5, 3))" }
    }
  }

  "when used with 'as[Int]' the parameter directive should" should {
    "extract a parameter value as Int" in {
      Get("/?amount=123") ~> {
        anyParams('amount.as[Int]) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "123" }
    }
    "cause a MalformedQueryParamRejection on illegal Int values" in {
      Get("/?amount=1x3") ~> {
        anyParams('amount.as[Int]) { echoComplete }
      } ~> check {
        inside(rejection) {
          case MalformedAnyParamRejection("amount", "'1x3' is not a valid 32-bit signed integer value", Some(_)) ⇒
        }
      }
    }
    "supply typed default values" in {
      Get() ~> {
        anyParams('amount ? 45) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "45" }
    }
    "create typed optional parameters that" should {
      "extract Some(value) when present" in {
        Get("/?amount=12") ~> {
          anyParams("amount".as[Int].?) { echoComplete }
        } ~> check { responseAs[String] shouldEqual "Some(12)" }
      }
      "extract None when not present" in {
        Get() ~> {
          anyParams("amount".as[Int].?) { echoComplete }
        } ~> check { responseAs[String] shouldEqual "None" }
      }
      "cause a MalformedQueryParamRejection on illegal Int values" in {
        Get("/?amount=x") ~> {
          anyParams("amount".as[Int].?) { echoComplete }
        } ~> check {
          inside(rejection) {
            case MalformedAnyParamRejection("amount", "'x' is not a valid 32-bit signed integer value", Some(_)) ⇒
          }
        }
      }
      "cause a MalformedRequestContentRejection on invalid query strings" in {
        Get("/?amount=1%2") ~> {
          anyParams("amount".as[Int].?) { echoComplete }
        } ~> check {
          inside(rejection) {
            case MalformedRequestContentRejection("The request's query string is invalid: amount=1%2", _) ⇒
          }
        }
      }
    }
    "supply chaining of unmarshallers" in {
      case class UserId(id: Int)
      case class AnotherUserId(id: Int)
      val UserIdUnmarshaller = Unmarshaller.strict[Int, UserId](UserId)
      implicit val AnotherUserIdUnmarshaller = Unmarshaller.strict[UserId, AnotherUserId](userId ⇒ AnotherUserId(userId.id))
      Get("/?id=45") ~> {
        anyParams('id.as[Int].as(UserIdUnmarshaller)) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "UserId(45)" }
      Get("/?id=45") ~> {
        anyParams('id.as[Int].as(UserIdUnmarshaller).as[AnotherUserId]) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "AnotherUserId(45)" }
    }
  }
}
