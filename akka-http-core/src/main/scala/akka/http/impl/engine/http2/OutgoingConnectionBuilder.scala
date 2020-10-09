/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.actor.ClassicActorSystemProvider
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.impl.engine.http2.Http2AlpnSupport
import akka.http.impl.engine.http2.Http2Blueprint
import akka.http.impl.util.LogByteStringTools
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.TLSClosing
import akka.stream.impl.io.TlsUtils
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.TLS
import javax.net.ssl.SSLEngine

import scala.concurrent.Future

/**
 * Builder for setting up a flow that will create one single connection per materialization to the specified host.
 * When customization is done, the flow is created using [[#connectionFlow()]].
 *
 * Not for user extension
 */
@DoNotInherit
trait OutgoingConnectionBuilder {
  /**
   * Change which host flows built with this builder connects to
   */
  def toHost(host: String): OutgoingConnectionBuilder

  /**
   * Change with port flows built with this builder connects to
   */
  def toPort(port: Int): OutgoingConnectionBuilder

  /**
   * Use a custom [[ConnectionContext]] for the connection.
   */
  def withConnectionContext(context: HttpsConnectionContext): OutgoingConnectionBuilder

  /**
   * Use custom [[ClientConnectionSettings]] for the connection.
   */
  def withClientConnectionSettings(settings: ClientConnectionSettings): OutgoingConnectionBuilder

  /**
   * Use a custom logger
   */
  def logTo(logger: LoggingAdapter): OutgoingConnectionBuilder

  /**
   * Create flow that when materialized creates a single connection to the HTTP server.
   *
   * Note that the responses are not guaranteed to arrive in the same order as the requests go out (In the case of a HTTP/2 server)
   * so therefore requests needs to have a [[akka.http.scaladsl.model.http2.RequestResponseAssociation]]
   * which Akka HTTP will carry over to the corresponding response for a request.
   */
  def connectionFlow(): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]]
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object OutgoingConnectionBuilder {

  def apply(host: String, port: Int, system: ClassicActorSystemProvider): OutgoingConnectionBuilder =
    Impl(
      host,
      port,
      clientConnectionSettings = ClientConnectionSettings(system),
      connectionContext = Http(system).defaultClientHttpsContext,
      log = system.classicSystem.log,
      system = system
    )

  private case class Impl(
    host:                     String,
    port:                     Int,
    clientConnectionSettings: ClientConnectionSettings,
    connectionContext:        HttpsConnectionContext,
    log:                      LoggingAdapter,
    system:                   ClassicActorSystemProvider) extends OutgoingConnectionBuilder {

    override def toHost(host: String): OutgoingConnectionBuilder = copy(host = host)

    override def toPort(port: Int): OutgoingConnectionBuilder = copy(port = port)

    override def withConnectionContext(context: HttpsConnectionContext): OutgoingConnectionBuilder = copy(connectionContext = context)

    override def withClientConnectionSettings(settings: ClientConnectionSettings): OutgoingConnectionBuilder = copy(clientConnectionSettings = settings)

    override def logTo(logger: LoggingAdapter): OutgoingConnectionBuilder = copy(log = logger)

    // FIXME should we make it `unorderedConnectionFlow()`?
    override def connectionFlow(): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
      def createEngine(): SSLEngine = {
        val engine = connectionContext.sslContextData match {
          // TODO FIXME configure hostname verification for this case
          case Left(ssl) =>
            val e = ssl.sslContext.createSSLEngine(host, port)
            TlsUtils.applySessionParameters(e, ssl.firstSession)
            e
          case Right(e) => e(Some((host, port)))
        }
        engine.setUseClientMode(true)
        Http2AlpnSupport.clientSetApplicationProtocols(engine, Array("h2"))
        engine
      }

      val stack = Http2Blueprint.clientStack(clientConnectionSettings, log) atop
        Http2Blueprint.unwrapTls atop
        LogByteStringTools.logTLSBidiBySetting("client-plain-text", clientConnectionSettings.logUnencryptedNetworkBytes) atop
        TLS(createEngine _, closing = TLSClosing.eagerClose)

      stack.joinMat(clientConnectionSettings.transport.connectTo(host, port, clientConnectionSettings)(system.classicSystem))(Keep.right)
    }

  }
}
