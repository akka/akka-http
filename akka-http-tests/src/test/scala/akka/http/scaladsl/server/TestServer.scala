/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.NotUsed
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.directives.Credentials
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.Http
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.http.scaladsl.model.headers.{ HttpOrigin, `Access-Control-Allow-Methods`, `Access-Control-Allow-Origin` }
import akka.http.scaladsl.server.util.ApplyConverter

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.io.StdIn

object SwaggerRoute {
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

  import spray.json.DefaultJsonProtocol._

  case class ParameterSpec(
    name: String,
    in:   String
  )
  case class ResponseSpec(
    summary: String
  )
  case class OperationSpec(
    summary:    String,
    parameters: Seq[ParameterSpec]        = Vector.empty,
    responses:  Map[String, ResponseSpec] = Map.empty
  )
  case class PathSpec(
    get:  Option[OperationSpec] = None,
    post: Option[OperationSpec] = None,
    put:  Option[OperationSpec] = None
  )

  case class OpenApi(
    title:   String,
    openapi: String,
    paths:   Map[String, PathSpec]
  )
  object OpenApi {
    implicit val paramaterSpecFormat = jsonFormat2(ParameterSpec.apply _)
    implicit val responseSpecFormat = jsonFormat1(ResponseSpec.apply _)
    implicit val operationSpecFormat = jsonFormat3(OperationSpec.apply _)
    implicit val pathSpecFormat = jsonFormat3(PathSpec.apply _)
    implicit val openApiFormat = jsonFormat3(OpenApi.apply _)
  }

  import Directives._
  def route(forRoute: Route): Route =
    complete(apiDescForRoute(forRoute))

  private def apiDescForRoute(route: Route): OpenApi = {
    case class RoutePath(segments: Seq[DirectiveRoute], last: Route) {
      override def toString: String = segments.map(_.directiveName).mkString(" -> ") + " -> " + last.getClass.toString
    }
    def leaves(route: Route, prefix: Seq[DirectiveRoute]): Seq[RoutePath] = route match {
      case AlternativeRoutes(alternatives) =>
        alternatives.flatMap(leaves(_, prefix))
      case dr: DirectiveRoute => leaves(dr.child, prefix :+ dr)
      case last               => Vector(RoutePath(prefix, last))
    }
    leaves(route, Vector.empty).foreach(println)

    // This is a static working example for the sample API. Ultimately, it should be possible to generate that directly from
    // inspecting the routes
    OpenApi(
      "Test API",
      "3.0.3",
      Map(
        "/pet" ->
          PathSpec(
            post = Some(OperationSpec("Add a new pet", responses = Map("200" -> ResponseSpec("Successfully added pet")))),
            put = Some(OperationSpec("Update an existing pet", responses = Map("200" -> ResponseSpec("Successfully updated pet"))))
          ),
        "/pet/{petId}" ->
          PathSpec(
            get = Some(
              OperationSpec(
                "Lookup a pet by id",
                parameters = Vector(ParameterSpec("petId", in = "path")),
                responses = Map("200" -> ResponseSpec("Successfully found pet")))
            )
          )
      )
    )
  }
}

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off
    """)

  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materializer = ActorMaterializer()

  import spray.json.DefaultJsonProtocol._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  final case class Tweet(message: String)
  implicit val tweetFormat = jsonFormat1(Tweet)

  implicit val jsonStreaming = EntityStreamingSupport.json()

  import ScalaXmlSupport._
  import Directives._

  def auth: AuthenticatorPF[String] = {
    case p @ Credentials.Provided(name) if p.verify(name + "-password") => name
  }

  // format: OFF
  val mainRoutes = {
    get {
      path("") {
        withRequestTimeout(1.milli, _ => HttpResponse(
          StatusCodes.EnhanceYourCalm,
          entity = "Unable to serve response within time limit, please enhance your calm.")) {
          Thread.sleep(1000)
          complete(index)
        }
      } ~
      path("secure") {
        authenticateBasicPF("My very secure site", auth) { user =>
          complete(<html> <body> Hello <b>{user}</b>. Access has been granted! </body> </html>)
        }
      } ~
      path("ping") {
        complete("PONG!")
      } ~
      path("crash") {
        complete(sys.error("BOOM!"))
      } ~
      path("tweet") {
        complete(Tweet("Hello, world!"))
      } ~
      (path("tweets") & parameter('n.as[Int])) { n =>
        get {
          val tweets = Source.repeat(Tweet("Hello, world!")).take(n)
          complete(tweets)
        } ~
        post {
          entity(asSourceOf[Tweet]) { tweets =>
            onComplete(tweets.runFold(0)({ case (acc, t) => acc + 1 })) { count =>
              complete(s"Total tweets received: " + count)
            }
          }
        } ~
        put {
          // checking the alternative syntax also works:
          entity(as[Source[Tweet, NotUsed]]) { tweets =>
            onComplete(tweets.runFold(0)({ case (acc, t) => acc + 1 })) { count =>
              complete(s"Total tweets received: " + count)
            }
          }
        }
      }
    } ~
    pathPrefix("inner")(getFromResourceDirectory("someDir"))
  }
  // format: ON

  val petStoreRoutes = {
    import DirectiveRoute._
    import DynamicDirective.dynamic

    pathPrefix("pet") {
      concat(
        pathEnd {
          concat(
            post {
              complete("posted")
            },
            put {
              complete("put")
            }
          )
        },
        path(IntNumber) { petId =>
          concat(
            get {
              dynamic { implicit ctx =>
                complete(s"Got [${petId}]")
              }
            },
            delete {
              dynamic { implicit ctx =>
                complete(s"Deleted [${petId.value}]")
              }
            }
          )
        }
      )
    }
  }

  val routes = /*mainRoutes ~ */
    respondWithHeader(`Access-Control-Allow-Origin`(HttpOrigin("http://localhost"))) {
      petStoreRoutes ~ path("openapi") {
        SwaggerRoute.route(petStoreRoutes)
      } ~ options {
        import akka.http.scaladsl.model.HttpMethods._
        complete(HttpResponse(
          status = 204,
          headers = `Access-Control-Allow-Methods`(POST, PUT, DELETE, GET) :: Nil
        ))
      }
    }

  val bindingFuture = Http().bindAndHandle(routes, interface = "0.0.0.0", port = 8080)

  println(s"Server online at http://0.0.0.0:8080/\nPress RETURN to stop...")
  StdIn.readLine()

  bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate())

  lazy val index =
    <html>
      <body>
        <h1>Say hello to <i>akka-http-core</i>!</h1>
        <p>Defined resources:</p>
        <ul>
          <li><a href="/ping">/ping</a></li>
          <li><a href="/secure">/secure</a> Use any username and '&lt;username&gt;-password' as credentials</li>
          <li><a href="/crash">/crash</a></li>
        </ul>
      </body>
    </html>
}
