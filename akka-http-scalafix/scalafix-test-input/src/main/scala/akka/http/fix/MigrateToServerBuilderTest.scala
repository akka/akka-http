/*
rule = MigrateToServerBuilder
*/

package akka.http.fix

import akka.actor._
import akka.event.LoggingAdapter
import akka.http.scaladsl._
import akka.http.scaladsl.server._
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.model._
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Sink }

import scala.concurrent.Future

object MigrateToServerBuilderTest {
  // Add code that needs fixing here.
  implicit def actorSystem: ActorSystem = ???
  def customMaterializer: Materializer = ???
  def http: HttpExt = ???
  implicit def log: LoggingAdapter = ???
  def settings: ServerSettings = ???
  def httpContext: HttpConnectionContext = ???
  def context: HttpsConnectionContext = ???
  def handler: HttpRequest => Future[HttpResponse] = ???
  def syncHandler: HttpRequest => HttpResponse = ???
  def flow: Flow[HttpRequest, HttpResponse, Any] = ???
  def route: Route = ???
  trait ServiceRoutes {
    def route: Route = ???
  }
  def service: ServiceRoutes = ???

  Http().bindAndHandleAsync(handler, "127.0.0.1", 8080, log = log)
  Http().bindAndHandleAsync(handler, "127.0.0.1", log = log, port = 8080)
  Http().bindAndHandleAsync(handler, "127.0.0.1", settings = settings)
  Http().bindAndHandleAsync(
    handler,
    interface = "localhost",
    port = 8443,
    context)
  Http().bindAndHandleAsync(
    handler,
    interface = "localhost",
    port = 8080,
    httpContext)
  Http().bindAndHandleAsync(
    handler,
    interface = "localhost",
    port = 8080,
    HttpConnectionContext)
  Http().bindAndHandle(flow, "127.0.0.1", port = 8080)
  Http().bindAndHandle(route, "127.0.0.1", port = 8080)
  Http().bindAndHandle(service.route, "127.0.0.1", port = 8080)
  Http().bindAndHandleSync(syncHandler, "127.0.0.1", log = log)

  Http().bind("127.0.0.1", settings = settings).runWith(Sink.ignore)

  // format: OFF
  Http().bindAndHandle(route, "127.0.0.1", port = 8080)(customMaterializer)// assert: MigrateToServerBuilder.custom-materializer-warning
  Http().bindAndHandleAsync(handler, "127.0.0.1", 8080)(customMaterializer)// assert: MigrateToServerBuilder.custom-materializer-warning
  Http().bindAndHandleSync(syncHandler, "127.0.0.1", 8080)(customMaterializer)// assert: MigrateToServerBuilder.custom-materializer-warning
  Http() // needed to appease formatter
  // format: ON

  http.bindAndHandle(route, "127.0.0.1", port = 8080)
  http.bindAndHandleAsync(handler, "127.0.0.1", 8080)
  http.bindAndHandleSync(syncHandler, "127.0.0.1", 8080)

  Http(actorSystem).bindAndHandle(route, "127.0.0.1", port = 8080)
  Http(actorSystem).bindAndHandleAsync(handler, "127.0.0.1", 8080)
  Http(actorSystem).bindAndHandleSync(syncHandler, "127.0.0.1", 8080)
}
