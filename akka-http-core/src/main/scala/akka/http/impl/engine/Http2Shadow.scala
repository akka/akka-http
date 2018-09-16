/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine

import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.{ ActorMaterializerHelper, Materializer }

import scala.concurrent.Future

/** INTERNAL API: Uses reflection to reach for Http2 support if available or fails with an exception */
@InternalApi
private[akka] object Http2Shadow {

  type ShadowHttp2 = AnyRef
  type ShadowHttp2Ext = {
    def bindAndHandleAsync(
      handler:   HttpRequest ⇒ Future[HttpResponse],
      interface: String, port: Int,
      httpContext: ConnectionContext,
      settings:    ServerSettings,
      parallelism: Int,
      log:         LoggingAdapter)(implicit fm: Materializer): Future[ServerBinding]
  }

  def bindAndHandleAsync(
    handler:   HttpRequest ⇒ Future[HttpResponse],
    interface: String, port: Int,
    httpContext: ConnectionContext,
    settings:    ServerSettings,
    parallelism: Int,
    log:         LoggingAdapter)(implicit fm: Materializer): Future[ServerBinding] = {

    val mat = ActorMaterializerHelper.downcast(fm)

    try {
      val system = mat.system.asInstanceOf[ExtendedActorSystem]
      val extensionIdClazz = system.dynamicAccess.getClassFor[ShadowHttp2]("akka.http.scaladsl.Http2").get

      val extensionInstance: ShadowHttp2Ext = extensionIdClazz.getMethod("get", Array(classOf[ActorSystem]): _*)
        .invoke(null, system).asInstanceOf[ShadowHttp2Ext]

      import scala.language.reflectiveCalls
      extensionInstance.bindAndHandleAsync(
        handler,
        interface, port,
        httpContext,
        settings,
        parallelism,
        log)(fm)
    } catch {
      case ex: Throwable ⇒ throw Http2SupportNotPresentException(ex)
    }
  }

  final case class Http2SupportNotPresentException(cause: Throwable)
    extends RuntimeException("Unable to invoke HTTP2 binding logic (as enabled setting `akka.http.server.enable-http2`). " +
      """Please make sure that `"com.typesafe.akka" %% "akka-http2-support" % AkkaHttpVersion` is on the classpath.""", cause)

}
