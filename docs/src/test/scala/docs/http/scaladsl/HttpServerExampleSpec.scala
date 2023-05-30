/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{ Directive, Route }
import akka.http.scaladsl.server.directives.FormFieldDirectives.FieldSpec
import akka.http.scaladsl.server.util.ConstructFromTuple
import akka.testkit.TestActors

import scala.annotation.nowarn
import docs.CompileOnlySpec

import scala.language.postfixOps
import scala.concurrent.{ Await, ExecutionContext, Future }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

@nowarn("msg=will not be a runnable program")
class HttpServerExampleSpec extends AnyWordSpec with Matchers
  with CompileOnlySpec {

  // never actually called
  val log: LoggingAdapter = null

  "binding-example" in compileOnlySpec {
    //#binding-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.stream.scaladsl._

    implicit val system = ActorSystem()
    implicit val executionContext = system.dispatcher

    val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] =
      Http().newServerAt("localhost", 8080).connectionSource()
    val bindingFuture: Future[Http.ServerBinding] =
      serverSource.to(Sink.foreach { connection => // foreach materializes the source
        println("Accepted new connection from " + connection.remoteAddress)
        // ... and then actually handle the connection
      }).run()
    //#binding-example
  }

  // mock values:
  val handleConnections = {
    import akka.stream.scaladsl.Sink
    Sink.ignore.mapMaterializedValue(_ => Future.failed(new Exception("")))
  }

  "binding-failure-handling" in compileOnlySpec {
    //#binding-failure-handling
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.Http.ServerBinding

    import scala.concurrent.Future

    implicit val system = ActorSystem()
    // needed for the future foreach in the end
    implicit val executionContext = system.dispatcher

    // let's say the OS won't allow us to bind to 80.
    val (host, port) = ("localhost", 80)
    val serverSource = Http().newServerAt(host, port).connectionSource()

    val bindingFuture: Future[ServerBinding] = serverSource
      .to(handleConnections) // Sink[Http.IncomingConnection, _]
      .run()

    bindingFuture.failed.foreach { ex =>
      log.error(ex, "Failed to bind to {}:{}!", host, port)
    }
    //#binding-failure-handling
  }

  object MyExampleMonitoringActor {
    def props = TestActors.echoActorProps
  }

  "incoming-connections-source-failure-handling" in compileOnlySpec {
    //#incoming-connections-source-failure-handling
    import akka.actor.ActorSystem
    import akka.actor.ActorRef
    import akka.http.scaladsl.Http
    import akka.stream.scaladsl.Flow

    implicit val system = ActorSystem()
    implicit val executionContext = system.dispatcher

    import Http._
    val (host, port) = ("localhost", 8080)
    val serverSource = Http().newServerAt(host, port).connectionSource()

    val failureMonitor: ActorRef = system.actorOf(MyExampleMonitoringActor.props)

    val reactToTopLevelFailures = Flow[IncomingConnection]
      .watchTermination()((_, termination) => termination.failed.foreach {
        cause => failureMonitor ! cause
      })

    serverSource
      .via(reactToTopLevelFailures)
      .to(handleConnections) // Sink[Http.IncomingConnection, _]
      .run()
    //#incoming-connections-source-failure-handling
  }

  "connection-stream-failure-handling" in compileOnlySpec {
    //#connection-stream-failure-handling
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.stream.scaladsl.Flow

    implicit val system = ActorSystem()
    implicit val executionContext = system.dispatcher

    val (host, port) = ("localhost", 8080)
    val serverSource = Http().newServerAt(host, port).connectionSource()

    val reactToConnectionFailure = Flow[HttpRequest]
      .recover[HttpRequest] {
        case ex =>
          // handle the failure somehow
          throw ex
      }

    val httpEcho = Flow[HttpRequest]
      .via(reactToConnectionFailure)
      .map { request =>
        // simple streaming (!) "echo" response:
        HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, request.entity.dataBytes))
      }

    serverSource
      .runForeach { con =>
        con.handleWith(httpEcho)
      }
    //#connection-stream-failure-handling
  }

  "full-server-example" in compileOnlySpec {
    //#full-server-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.HttpMethods._
    import akka.http.scaladsl.model._
    import akka.stream.scaladsl.Sink

    implicit val system = ActorSystem()
    implicit val executionContext = system.dispatcher

    val serverSource = Http().newServerAt("localhost", 8080).connectionSource()

    val requestHandler: HttpRequest => HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
        HttpResponse(entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          "<html><body>Hello world!</body></html>"))

      case HttpRequest(GET, Uri.Path("/ping"), _, _, _) =>
        HttpResponse(entity = "PONG!")

      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) =>
        sys.error("BOOM!")

      case r: HttpRequest =>
        r.discardEntityBytes() // important to drain incoming HTTP Entity stream
        HttpResponse(404, entity = "Unknown resource!")
    }

    val bindingFuture: Future[Http.ServerBinding] =
      serverSource.to(Sink.foreach { connection =>
        println("Accepted new connection from " + connection.remoteAddress)

        connection handleWithSyncHandler requestHandler
        // this is equivalent to
        // connection handleWith { Flow[HttpRequest] map requestHandler }
      }).run()
    //#full-server-example
  }

  "long-routing-example" in compileOnlySpec {
    //#long-routing-example
    import akka.actor.{ ActorRef, ActorSystem }
    import akka.http.scaladsl.coding.Coders
    import akka.http.scaladsl.marshalling.ToResponseMarshaller
    import akka.http.scaladsl.model.StatusCodes.MovedPermanently
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
    import akka.pattern.ask
    import akka.util.Timeout

    // types used by the API routes
    type Money = Double // only for demo purposes, don't try this at home!
    type TransactionResult = String
    case class User(name: String)
    case class Order(email: String, amount: Money)
    case class Update(order: Order)
    case class OrderItem(i: Int, os: Option[String], s: String)

    // marshalling would usually be derived automatically using libraries
    implicit val orderUM: FromRequestUnmarshaller[Order] = ???
    implicit val orderM: ToResponseMarshaller[Order] = ???
    implicit val orderSeqM: ToResponseMarshaller[Seq[Order]] = ???
    implicit val timeout: Timeout = ??? // for actor asks
    implicit val ec: ExecutionContext = ???
    implicit val sys: ActorSystem = ???

    // backend entry points
    def myAuthenticator: Authenticator[User] = ???
    def retrieveOrdersFromDB: Future[Seq[Order]] = ???
    def myDbActor: ActorRef = ???
    def processOrderRequest(id: Int, complete: Order => Unit): Unit = ???

    lazy val binding = Http().newServerAt("localhost", 8080).bind(topLevelRoute)
    // ...

    lazy val topLevelRoute: Route =
      // provide top-level path structure here but delegate functionality to subroutes for readability
      concat(
        path("orders")(ordersRoute),
        // extract URI path element as Int
        pathPrefix("order" / IntNumber)(orderRoute),
        pathPrefix("documentation")(documentationRoute),
        path("oldApi" / Remaining) { pathRest =>
          redirect("http://oldapi.example.com/" + pathRest, MovedPermanently)
        }
      )

    // For bigger routes, these sub-routes can be moved to separate files
    lazy val ordersRoute: Route =
      authenticateBasic(realm = "admin area", myAuthenticator) { user =>
        concat(
          get {
            encodeResponseWith(Coders.Deflate) {
              complete {
                // unpack future and marshal custom object with in-scope marshaller
                retrieveOrdersFromDB
              }
            }
          },
          post {
            // decompress gzipped or deflated requests if required
            decodeRequest {
              // unmarshal with in-scope unmarshaller
              entity(as[Order]) { order =>
                complete {
                  // ... write order to DB
                  "Order received"
                }
              }
            }
          }
        )
      }

    def orderRoute(orderId: Int): Route =
      concat(
        pathEnd {
          concat(
            put {
              formFields("email", "total".as[Money]).as(Order.apply _) { (order: Order) =>
                complete {
                  // complete with serialized Future result
                  (myDbActor ? Update(order)).mapTo[TransactionResult]
                }
              }
            },
            get {
              // debugging helper
              logRequest("GET-ORDER") {
                // use in-scope marshaller to create completer function
                completeWith(instanceOf[Order]) { completer =>
                  // custom
                  processOrderRequest(orderId, completer)
                }
              }
            })
        },
        path("items") {
          get {
            // parameters to case class extraction
            parameters("size".as[Int], "color".optional, "dangerous".withDefault("no"))
              .as(OrderItem.apply _) { (orderItem: OrderItem) =>
                // ... route using case class instance created from
                // required and optional query parameters
                complete("") // #hide
              }
          }
        })

    lazy val documentationRoute: Route =
      // optionally compresses the response with Gzip or Deflate
      // if the client accepts compressed responses
      encodeResponse {
        // serve up static content from a JAR resource
        getFromResourceDirectory("docs")
      }
    //#long-routing-example
  }

  "consume entity using entity directive" in compileOnlySpec {
    //#consume-entity-directive
    import akka.actor.ActorSystem
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    import spray.json.DefaultJsonProtocol._
    import spray.json.RootJsonFormat

    implicit val system: ActorSystem = ActorSystem()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext: ExecutionContext = system.dispatcher

    final case class Bid(userId: String, bid: Int)

    // these are from spray-json
    implicit val bidFormat: RootJsonFormat[Bid] = jsonFormat2(Bid.apply)

    val route =
      path("bid") {
        put {
          entity(as[Bid]) { (bid: Bid) =>
            // incoming entity is fully consumed and converted into a Bid
            complete("The bid was: " + bid)
          }
        }
      }
    //#consume-entity-directive
  }

  "consume entity using raw dataBytes to file" in compileOnlySpec {
    //#consume-raw-dataBytes
    import akka.actor.ActorSystem
    import akka.stream.scaladsl.FileIO
    import akka.http.scaladsl.server.Directives._
    import java.io.File

    implicit val system = ActorSystem()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      (put & path("lines")) {
        withoutSizeLimit {
          extractDataBytes { bytes =>
            val finishedWriting = bytes.runWith(FileIO.toPath(new File("/tmp/example.out").toPath))

            // we only want to respond once the incoming data has been handled:
            onComplete(finishedWriting) { ioResult =>
              complete("Finished writing data: " + ioResult)
            }
          }
        }
      }
    //#consume-raw-dataBytes
  }

  "drain entity using request#discardEntityBytes" in compileOnlySpec {
    //#discard-discardEntityBytes
    import akka.actor.ActorSystem
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.model.HttpRequest

    implicit val system = ActorSystem()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      (put & path("lines")) {
        withoutSizeLimit {
          extractRequest { (r: HttpRequest) =>
            val finishedWriting = r.discardEntityBytes().future

            // we only want to respond once the incoming data has been handled:
            onComplete(finishedWriting) { done =>
              complete("Drained all data from connection... (" + done + ")")
            }
          }
        }
      }
    //#discard-discardEntityBytes
  }

  "discard entity manually" in compileOnlySpec {
    //#discard-close-connections
    import akka.actor.ActorSystem
    import akka.stream.scaladsl.Sink
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.model.headers.Connection

    implicit val system = ActorSystem()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      (put & path("lines")) {
        withoutSizeLimit {
          extractDataBytes { data =>
            // Closing connections, method 1 (eager):
            // we deem this request as illegal, and close the connection right away:
            data.runWith(Sink.cancelled) // "brutally" closes the connection

            // Closing connections, method 2 (graceful):
            // consider draining connection and replying with `Connection: Close` header
            // if you want the client to close after this request/reply cycle instead:
            respondWithHeader(Connection("close"))
            complete(StatusCodes.Forbidden -> "Not allowed!")
          }
        }
      }
    //#discard-close-connections
  }

  "dynamic routing example" in compileOnlySpec {
    import akka.actor.ActorSystem
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.server.Route
    import spray.json.DefaultJsonProtocol._
    import spray.json._

    implicit val system: ActorSystem = ActorSystem()

    //#dynamic-routing-example
    case class MockDefinition(path: String, requests: Seq[JsValue], responses: Seq[JsValue])
    implicit val format: RootJsonFormat[MockDefinition] = jsonFormat3(MockDefinition.apply)

    @volatile var state = Map.empty[String, Map[JsValue, JsValue]]

    // fixed route to update state
    val fixedRoute: Route = post {
      pathSingleSlash {
        entity(as[MockDefinition]) { mock =>
          val mapping = mock.requests.zip(mock.responses).toMap
          state = state + (mock.path -> mapping)
          complete("ok")
        }
      }
    }

    // dynamic routing based on current state
    val dynamicRoute: Route = ctx => {
      val routes = state.map {
        case (segment, responses) =>
          post {
            path(segment) {
              entity(as[JsValue]) { input =>
                complete(responses.get(input))
              }
            }
          }
      }
      concat(routes.toList: _*)(ctx)
    }

    val route = fixedRoute ~ dynamicRoute
    //#dynamic-routing-example
  }

  "graceful termination" in compileOnlySpec {
    //#graceful-termination
    import akka.actor.ActorSystem
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.server.Route
    import scala.concurrent.duration._

    implicit val system = ActorSystem()
    implicit val dispatcher = system.dispatcher

    val routes = get {
      complete("Hello world!")
    }

    val binding: Future[Http.ServerBinding] =
      Http().newServerAt("127.0.0.1", 8080).bind(routes)

    // ...
    // once ready to terminate the server, invoke terminate:
    val onceAllConnectionsTerminated: Future[Http.HttpTerminated] =
      Await.result(binding, 10.seconds)
        .terminate(hardDeadline = 3.seconds)

    // once all connections are terminated,
    // - you can invoke coordinated shutdown to tear down the rest of the system:
    onceAllConnectionsTerminated.flatMap { _ =>
      system.terminate()
    }

    //#graceful-termination
  }
}
