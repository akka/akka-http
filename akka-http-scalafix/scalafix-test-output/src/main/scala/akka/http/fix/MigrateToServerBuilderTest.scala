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

  Http().newServerAt("127.0.0.1", 8080).logTo(log).bind(handler)
  Http().newServerAt("127.0.0.1", 8080).logTo(log).bind(handler)
  Http().newServerAt("127.0.0.1", 0).withSettings(settings).bind(handler)
  Http().newServerAt(interface = "localhost", port = 8443).enableHttps(context).bind(handler)
  Http().newServerAt(interface = "localhost", port = 8080).bind(handler)
  Http().newServerAt(interface = "localhost", port = 8080).bind(handler)
  Http().newServerAt("127.0.0.1", 8080).bindFlow(flow)
  Http().newServerAt("127.0.0.1", 8080).bind(route)
  Http().newServerAt("127.0.0.1", 8080).bind(service.route)
  Http().newServerAt("127.0.0.1", 0).logTo(log).bindSync(syncHandler)

  Http().newServerAt("127.0.0.1", 0).withSettings(settings).connectionSource().runWith(Sink.ignore)

  // format: OFF
  Http().newServerAt("127.0.0.1", 8080).withMaterializer(customMaterializer).bind(route)
  Http().newServerAt("127.0.0.1", 8080).withMaterializer(customMaterializer).bind(handler)
  Http().newServerAt("127.0.0.1", 8080).withMaterializer(customMaterializer).bindSync(syncHandler)
  Http() // needed to appease formatter
  // format: ON

  http.newServerAt("127.0.0.1", 8080).bind(route)
  http.newServerAt("127.0.0.1", 8080).bind(handler)
  http.newServerAt("127.0.0.1", 8080).bindSync(syncHandler)

  Http(actorSystem).newServerAt("127.0.0.1", 8080).bind(route)
  Http(actorSystem).newServerAt("127.0.0.1", 8080).bind(handler)
  Http(actorSystem).newServerAt("127.0.0.1", 8080).bindSync(syncHandler)
}
