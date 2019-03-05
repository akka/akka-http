/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.util.concurrent.CompletionStage
import java.util.function.{ Function ⇒ JFunction }

import akka.http.javadsl.model.{ Uri, HttpRequest ⇒ JHttpRequest, HttpResponse ⇒ JHttpResponse }
import akka.http.javadsl.server.Route
import akka.http.scaladsl.model
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.server.directives.ReverseProxyDirectives.{ ReverseProxyTargetConfig, ReverseProxyTargetMagnet }
import akka.http.scaladsl.server.{ Directives ⇒ D }

import scala.compat.java8.FunctionConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.Future

abstract class ReverseProxyDirectives extends FramedEntityStreamingDirectives {
  import akka.http.impl.util.JavaMapping.Implicits._

  private val javaToScalaResponse = ((res: JHttpResponse) ⇒ res.asScala).asJava

  def reverseProxy(
    targetUri:        Uri,
    useUnmatchedPath: Boolean,
    httpClient:       JFunction[JHttpRequest, CompletionStage[JHttpResponse]]
  ): Route = {
    def _client = httpClient
    RouteAdapter(
      D.reverseProxy(new ReverseProxyTargetMagnet {
        val config = ReverseProxyTargetConfig(targetUri.asScala, useUnmatchedPath)
        val httpClient: model.HttpRequest ⇒ Future[model.HttpResponse] =
          req ⇒ _client(req).thenApply[HttpResponse](javaToScalaResponse).toScala
      })
    )
  }
}
