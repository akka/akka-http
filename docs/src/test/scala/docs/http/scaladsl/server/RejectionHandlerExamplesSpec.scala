/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RoutingSpec
import docs.CompileOnlySpec

object MyRejectionHandler {

  //#custom-handler-example
  import akka.actor.ActorSystem
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.model._
  import akka.http.scaladsl.server._
  import StatusCodes._
  import Directives._

  object MyApp extends App {
    def myRejectionHandler =
      RejectionHandler.newBuilder()
        .handle {
          case MissingCookieRejection(cookieName) =>
            complete(HttpResponse(BadRequest, entity = "No cookies, no service!!!"))
        }
        .handle {
          case AuthorizationFailedRejection =>
            complete(Forbidden, "You're out of your depth!")
        }
        .handle {
          case ValidationRejection(msg, _) =>
            complete(InternalServerError, "That wasn't valid! " + msg)
        }
        .handleAll[MethodRejection] { methodRejections =>
          val names = methodRejections.map(_.supported.name)
          complete(MethodNotAllowed, s"Can't do that! Supported: ${names mkString " or "}!")
        }
        .handleNotFound { complete((NotFound, "Not here!")) }
        .result()

    implicit val system: ActorSystem = ActorSystem()

    val route: Route = handleRejections(myRejectionHandler) {
      // ... some route structure
      null // #hide
    }

    Http().newServerAt("localhost", 8080).bind(route)
  }
  //#custom-handler-example
}

object HandleNotFoundWithThePath {

  //#not-found-with-path
  import akka.http.scaladsl.model.StatusCodes._
  import akka.http.scaladsl.server._
  import Directives._

  implicit def myRejectionHandler: RejectionHandler =
    RejectionHandler.newBuilder()
      .handleNotFound {
        extractUnmatchedPath { p =>
          complete(NotFound, s"The path you requested [${p}] does not exist.")
        }
      }
      .result()
  //#not-found-with-path
}

class RejectionHandlerExamplesSpec extends RoutingSpec with CompileOnlySpec {

  "example-1" in {
    //#example-1
    import akka.http.scaladsl.coding.Coders

    val route =
      path("order") {
        concat(
          get {
            complete("Received GET")
          },
          post {
            decodeRequestWith(Coders.Gzip) {
              complete("Received compressed POST")
            }
          }
        )
      }
    //#example-1
  }

  "example-2-all-exceptions-json" in {
    //#example-json
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server.RejectionHandler

    implicit def myRejectionHandler: RejectionHandler =
      RejectionHandler.default
        .mapRejectionResponse {
          case res @ HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
            // since all Akka default rejection responses are Strict this will handle all rejections
            val message = ent.data.utf8String.replaceAll("\"", """\"""")

            // we copy the response in order to keep all headers and status code, wrapping the message as hand rolled JSON
            // you could the entity using your favourite marshalling library (e.g. spray json or anything else)
            res.withEntity(HttpEntity(ContentTypes.`application/json`, s"""{"rejection": "$message"}"""))

          case x => x // pass through all other types of responses
        }

    val route =
      Route.seal(
        path("hello") {
          complete("Hello there")
        }
      )

    // tests:
    Get("/nope") ~> route ~> check {
      status shouldEqual StatusCodes.NotFound
      contentType shouldEqual ContentTypes.`application/json`
      responseAs[String] shouldEqual """{"rejection": "The requested resource could not be found."}"""
    }
    //#example-json
  }

  "example-3-custom-rejection-http-response" in {
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server.RejectionHandler

    implicit def myRejectionHandler: RejectionHandler =
      RejectionHandler.default
        .mapRejectionResponse {
          case res @ HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
            // since all Akka default rejection responses are Strict this will handle all rejections
            val message = ent.data.utf8String.replaceAll("\"", """\"""")

            // we copy the response in order to keep all headers and status code, wrapping the message as hand rolled JSON
            // you could the entity using your favourite marshalling library (e.g. spray json or anything else)
            res.withEntity(HttpEntity(ContentTypes.`application/json`, s"""{"rejection": "$message"}"""))

          case x => x // pass through all other types of responses
        }

    //#example-json

    val anotherRoute =
      Route.seal(
        validate(check = false, "Whoops, bad request!") {
          complete("Hello there")
        }
      )

    // tests:
    Get("/hello") ~> anotherRoute ~> check {
      status shouldEqual StatusCodes.BadRequest
      contentType shouldEqual ContentTypes.`application/json`
      responseAs[String] shouldEqual """{"rejection": "Whoops, bad request!"}"""
    }
    //#example-json
  }

  "test custom handler example" in {
    import akka.http.scaladsl.server._
    import akka.http.scaladsl.model.StatusCodes.BadRequest

    implicit def myRejectionHandler: RejectionHandler = RejectionHandler.newBuilder().handle {
      case MissingCookieRejection(_) => complete(HttpResponse(BadRequest, entity = "No cookies, no service!!!"))
    }.result()

    val route = Route.seal(reject(MissingCookieRejection("abc")))

    // tests:
    Get() ~> route ~> check {
      responseAs[String] shouldEqual "No cookies, no service!!!"
    }
  }
}
