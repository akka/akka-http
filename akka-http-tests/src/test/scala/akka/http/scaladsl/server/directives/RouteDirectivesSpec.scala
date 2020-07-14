/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport

import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import akka.testkit.EventFilter
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.server._
import akka.http.scaladsl.model._
import headers._
import StatusCodes._
import org.scalatest.wordspec.AnyWordSpec

class RouteDirectivesSpec extends AnyWordSpec with GenericRoutingSpec {

  "The `complete` directive" should {
    "be chainable with the `&` operator" in {
      Get() ~> (get & complete("yeah")) ~> check { responseAs[String] shouldEqual "yeah" }
    }
    "be lazy in its argument evaluation, independently of application style" in {
      var i = 0
      Put() ~> {
        get { complete { i += 1; "get" } } ~
          put { complete { i += 1; "put" } } ~
          (post & complete { i += 1; "post" })
      } ~> check {
        responseAs[String] shouldEqual "put"
        i shouldEqual 1
      }
    }
    "be lazy in its argument evaluation even when passing in a status code" in {
      var i = 0
      Put() ~> {
        get { complete(OK, { i += 1; "get" }) } ~
          put { complete(OK, { i += 1; "put" }) } ~
          (post & complete(OK, { i += 1; "post" }))
      } ~> check {
        responseAs[String] shouldEqual "put"
        i shouldEqual 1
      }
    }
    "support completion from response futures" should {
      "simple case without marshaller" in {
        Get() ~> {
          get & complete(Promise.successful(HttpResponse(entity = "yup")).future)
        } ~> check { responseAs[String] shouldEqual "yup" }
      }
      "for successful futures and marshalling" in {
        Get() ~> complete(Promise.successful("yes").future) ~> check { responseAs[String] shouldEqual "yes" }
      }
      object TestException extends RuntimeException("Boom")
      "for failed futures and marshalling" in EventFilter[TestException.type](
        occurrences = 1,
        message = BasicRouteSpecs.defaultExnHandler500Error("Boom")
      ).intercept {
          Get() ~> complete(Promise.failed[String](TestException).future) ~>
            check {
              status shouldEqual StatusCodes.InternalServerError
              responseAs[String] shouldEqual "There was an internal server error."
            }
        }
      "for futures failed with a RejectionError" in {
        Get() ~> complete(Promise.failed[String](RejectionError(AuthorizationFailedRejection)).future) ~>
          check {
            rejection shouldEqual AuthorizationFailedRejection
          }
      }
    }
    "allow easy handling of futured ToResponseMarshallers" in {
      trait RegistrationStatus
      case class Registered(name: String) extends RegistrationStatus
      case object AlreadyRegistered extends RegistrationStatus

      val route =
        get {
          path("register" / Segment) { name =>
            def registerUser(name: String): Future[RegistrationStatus] = Future.successful {
              name match {
                case "otto" => AlreadyRegistered
                case _      => Registered(name)
              }
            }
            complete {
              registerUser(name).map[ToResponseMarshallable] {
                case Registered(_) => HttpEntity.Empty
                case AlreadyRegistered =>
                  import spray.json.DefaultJsonProtocol._
                  import SprayJsonSupport._
                  StatusCodes.BadRequest -> Map("error" -> "User already Registered")
              }
            }
          }
        }

      Get("/register/otto") ~> route ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
      Get("/register/karl") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual ""
      }
    }
    "do Content-Type negotiation for multi-marshallers" in {
      val route = get & complete(Data("Ida", 83))

      import akka.http.scaladsl.model.headers.Accept
      Get().withHeaders(Accept(MediaTypes.`application/json`)) ~> route ~> check {
        responseAs[String] shouldEqual
          """{"age":83,"name":"Ida"}"""
      }
      Get().withHeaders(Accept(MediaTypes.`text/xml`)) ~> route ~> check {
        responseAs[xml.NodeSeq] shouldEqual <data><name>Ida</name><age>83</age></data>
      }
      Get().withHeaders(Accept(MediaTypes.`text/plain`)) ~> Route.seal(route) ~> check {
        status shouldEqual StatusCodes.NotAcceptable
      }
    }
    "avoid marshalling too eagerly for multi-marshallers" in {
      case class MyClass(value: String)

      implicit val superMarshaller = {
        val jsonMarshaller =
          Marshaller.stringMarshaller(MediaTypes.`application/json`)
            .compose[MyClass] { mc =>
              println(s"jsonMarshaller marshall $mc")
              mc.value
            }
        val textMarshaller = Marshaller.stringMarshaller(MediaTypes.`text/html`)
          .compose[MyClass] { mc =>
            println(s"textMarshaller marshall $mc")
            throw new IllegalArgumentException(s"Unexpected value $mc")
          }

        Marshaller.oneOf(jsonMarshaller, textMarshaller)
      }
      val request =
        HttpRequest(uri = "/test")
          .withHeaders(Accept(MediaTypes.`application/json`))
      val response = Await.result(Marshal(MyClass("test")).toResponseFor(request), 1.second)
      response.status shouldEqual StatusCodes.OK
    }
  }

  "the redirect directive" should {
    "produce proper 'Found' redirections" in {
      Get() ~> {
        redirect("/foo", Found)
      } ~> check {
        response shouldEqual HttpResponse(
          status = 302,
          entity = HttpEntity(
            ContentTypes.`text/html(UTF-8)`,
            "The requested resource temporarily resides under <a href=\"/foo\">this URI</a>."),
          headers = Location("/foo") :: Nil)
      }
    }

    "produce proper 'NotModified' redirections" in {
      Get() ~> {
        redirect("/foo", NotModified)
      } ~> check { response shouldEqual HttpResponse(304, headers = Location("/foo") :: Nil) }
    }
  }

  "the handle directive" should {
    "use a function to complete a request" in {
      Get(Uri("https://akka.io/foo")) ~> {
        handle(req => Future.successful(HttpResponse(OK, entity = req.uri.toString)))
      } ~> check { response shouldEqual HttpResponse(200, entity = "https://akka.io/foo") }
    }
    "fail the request when the future fails" in {
      Get(Uri("https://akka.io/foo")) ~> {
        concat(
          handle(req => Future.failed(new IllegalStateException("Some error"))),
          complete(ImATeapot)
        )
      } ~> check { response shouldEqual HttpResponse(500, entity = "There was an internal server error.") }
    }
    "fail the request when the function throws" in {
      Get(Uri("https://akka.io/foo")) ~> {
        concat(
          handle(req => throw new IllegalStateException("Some error")),
          complete(ImATeapot)
        )
      } ~> check { response shouldEqual HttpResponse(500, entity = "There was an internal server error.") }
    }
  }

  "the handle directive with PartialFunction" should {
    val handler: PartialFunction[HttpRequest, Future[HttpResponse]] = {
      case HttpRequest(HttpMethods.GET, Uri.Path("/value"), _, _, _) =>
        Future.successful(HttpResponse(entity = "23"))
      case HttpRequest(HttpMethods.GET, Uri.Path("/fail"), _, _, _) =>
        Future.failed(new RuntimeException("failure!"))
      case HttpRequest(HttpMethods.GET, Uri.Path("/throw"), _, _, _) =>
        throw new RuntimeException("oops")
    }
    val theRejection = MethodRejection(HttpMethods.POST)
    val route = handle(handler, theRejection :: Nil)

    "use a PartialFunction to complete a request" in {
      Get("/value") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual "23"
      }
    }
    "reject if the function is not defined for the request" in {
      Get("/other") ~> route ~> check {
        rejection shouldEqual theRejection
      }
    }

    "fail if the function returns a failure" in EventFilter[RuntimeException](occurrences = 1).intercept {
      Get("/fail") ~> route ~> check {
        status.intValue shouldBe 500
      }
    }

    "fail if the function throws" in EventFilter[RuntimeException](occurrences = 1).intercept {
      Get("/throw") ~> route ~> check {
        status.intValue shouldBe 500
      }
    }
  }

  "the handleSync directive with PartialFunction" should {
    val handler: PartialFunction[HttpRequest, HttpResponse] = {
      case HttpRequest(HttpMethods.GET, Uri.Path("/value"), _, _, _) =>
        HttpResponse(entity = "23")
      case HttpRequest(HttpMethods.GET, Uri.Path("/throw"), _, _, _) =>
        throw new RuntimeException("oops")
    }
    val theRejection = MethodRejection(HttpMethods.POST)
    val route = handleSync(handler, theRejection :: Nil)

    "use a PartialFunction to complete a request" in {
      Get("/value") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual "23"
      }
    }
    "reject if the function is not defined for the request" in {
      Get("/other") ~> route ~> check {
        rejection shouldEqual theRejection
      }
    }

    "fail if the function throws" in EventFilter[RuntimeException](occurrences = 1).intercept {
      Get("/throw") ~> route ~> check {
        status.intValue shouldBe 500
      }
    }
  }

  case class Data(name: String, age: Int)
  object Data {
    import spray.json.DefaultJsonProtocol._
    import SprayJsonSupport._
    import ScalaXmlSupport._

    val jsonMarshaller: ToEntityMarshaller[Data] = jsonFormat2(Data.apply)

    val xmlMarshaller: ToEntityMarshaller[Data] = Marshaller.combined { (data: Data) =>
      <data><name>{ data.name }</name><age>{ data.age }</age></data>
    }

    implicit val dataMarshaller: ToResponseMarshaller[Data] =
      Marshaller.oneOf(jsonMarshaller, xmlMarshaller)
  }
}
