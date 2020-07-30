package fix

import akka.actor._
import akka.event.LoggingAdapter
import akka.http.scaladsl._
import akka.http.scaladsl.server._
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{ Flow, Sink }

import scala.concurrent.Future

object BindToServerBuilderApiTest {
  // Add code that needs fixing here.
  implicit def actorSystem: ActorSystem = ???
  implicit def log: LoggingAdapter = ???
  def settings: ServerSettings = ???
  def context: HttpsConnectionContext = ???
  def handler: HttpRequest => Future[HttpResponse] = ???
  def syncHandler: HttpRequest => HttpResponse = ???
  def flow: Flow[HttpRequest, HttpResponse, Any] = ???
  def route: Route = ???

  // fix: HttpConnectionContext
  // fix: materializer or system explicitly given

  Http().newServerAt("127.0.0.1", 8080).logTo(log).bind(handler)
  Http().newServerAt("127.0.0.1", 8080).logTo(log).bind(handler)
  Http().newServerAt("127.0.0.1", 0).withSettings(settings).bind(handler)
  Http().newServerAt(interface = "localhost", port = 8443).enableHttps(context).bind(handler)
  Http().newServerAt("127.0.0.1", 8080).bindFlow(flow)
  Http().newServerAt("127.0.0.1", 8080).bind(route)
  Http().newServerAt("127.0.0.1", 0).logTo(log).bindSync(syncHandler)

  Http().newServerAt("127.0.0.1", 0).withSettings(settings).connectionSource().runWith(Sink.ignore)
}
