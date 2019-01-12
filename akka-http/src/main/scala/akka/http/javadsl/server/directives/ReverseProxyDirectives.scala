package akka.http.javadsl.server.directives

import java.util.concurrent.CompletionStage
import java.util.function.{ Function ⇒ JFunction }

import akka.http.javadsl.model.{ HttpRequest, HttpResponse, Uri }
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.directives.ReverseProxyDirectives.{ ReverseProxyTargetConfig, ReverseProxyTargetMagnet }
import akka.http.scaladsl.server.{ Directives ⇒ D }
import scala.compat.java8.FutureConverters._

abstract class ReverseProxyDirectives {
  import akka.http.impl.util.JavaMapping.Implicits._

  def reverseProxy(
    targetUri:        Uri,
    useUnmatchedPath: Boolean,
    httpClient:       JFunction[HttpRequest, CompletionStage[HttpResponse]]
  ): Route = {
    def _client = httpClient
    RouteAdapter(
      D.reverseProxy(new ReverseProxyTargetMagnet {
        val config = ReverseProxyTargetConfig(targetUri.asScala, useUnmatchedPath)
        val httpClient = req ⇒ _client(req).thenApply[akka.http.scaladsl.model.HttpResponse](res ⇒ res.asScala).toScala
      })
    )
  }
}
