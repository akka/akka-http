/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.util.concurrent.CompletionStage

import akka.actor.ClassicActorSystemProvider
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.event.LoggingAdapter
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
import akka.stream.scaladsl.TLSPlacebo
import javax.net.ssl.SSLEngine
import akka.http.javadsl
import akka.http.javadsl.{ OutgoingConnectionBuilder => JOutgoingConnectionBuilder }
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.Http
import akka.http.scaladsl.HttpsConnectionContext
import akka.http.scaladsl.OutgoingConnectionBuilder
import akka.stream.javadsl.{ Flow => JFlow }

import scala.concurrent.Future

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object OutgoingConnectionBuilderImpl {

  def apply(host: String, port: Int, system: ClassicActorSystemProvider): OutgoingConnectionBuilder =
    Impl(
      host,
      port,
      clientConnectionSettings = ClientConnectionSettings(system),
      connectionContext = Http(system).defaultClientHttpsContext,
      log = system.classicSystem.log,
      system = system,
      usingHttp2 = false
    )

  private final case class Impl(
    host:                     String,
    port:                     Int,
    clientConnectionSettings: ClientConnectionSettings,
    connectionContext:        ConnectionContext,
    log:                      LoggingAdapter,
    system:                   ClassicActorSystemProvider,
    usingHttp2:               Boolean) extends OutgoingConnectionBuilder {

    override def toHost(host: String): OutgoingConnectionBuilder = copy(host = host)

    override def toPort(port: Int): OutgoingConnectionBuilder = copy(port = port)

    override def withConnectionContext(context: ConnectionContext): OutgoingConnectionBuilder = copy(connectionContext = context)

    override def withClientConnectionSettings(settings: ClientConnectionSettings): OutgoingConnectionBuilder = copy(clientConnectionSettings = settings)

    override def logTo(logger: LoggingAdapter): OutgoingConnectionBuilder = copy(log = logger)

    override def http2(): OutgoingConnectionBuilder =
      copy(usingHttp2 = true).toPort(443).withConnectionContext(Http(system).defaultClientHttpsContext)

    override def https1(): OutgoingConnectionBuilder =
      toPort(443).withConnectionContext(Http(system).defaultClientHttpsContext)

    override def insecureHttp1(): OutgoingConnectionBuilder =
      toPort(80).withConnectionContext(ConnectionContext.noEncryption())

    override def insecureForcedHttp2(): OutgoingConnectionBuilder =
      copy(usingHttp2 = true).insecureHttp1()

    override private[akka] def toJava: JOutgoingConnectionBuilder = new JavaAdapter(this)

    // FIXME should we make it `unorderedConnectionFlow()`?
    override def connectionFlow(): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
      val stack = connectionContext match {
        case connectionContext: HttpsConnectionContext =>
          if (usingHttp2) {
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

            Http2Blueprint.clientStack(clientConnectionSettings, log) atop
              Http2Blueprint.unwrapTls atop
              LogByteStringTools.logTLSBidiBySetting("client-plain-text", clientConnectionSettings.logUnencryptedNetworkBytes) atop
              TLS(createEngine _, closing = TLSClosing.eagerClose)
          } else {
            // FIXME
            throw new UnsupportedOperationException("tls http1 not implemented yet")
          }
        case _ =>
          if (usingHttp2) {
            Http2Blueprint.clientStack(clientConnectionSettings, log) atop
              Http2Blueprint.unwrapTls atop
              LogByteStringTools.logTLSBidiBySetting("client-plain-text", clientConnectionSettings.logUnencryptedNetworkBytes) atop
              TLSPlacebo()

          } else {
            throw new UnsupportedOperationException("insecure http1 not implemented yet")
          }
      }

      stack.joinMat(clientConnectionSettings.transport.connectTo(host, port, clientConnectionSettings)(system.classicSystem))(Keep.right)
    }

  }

  private class JavaAdapter(actual: Impl) extends JOutgoingConnectionBuilder {
    override def toHost(host: String): JOutgoingConnectionBuilder = new JavaAdapter(actual.toHost(host).asInstanceOf[Impl])

    override def toPort(port: Int): JOutgoingConnectionBuilder = new JavaAdapter(actual.toPort(port).asInstanceOf[Impl])

    override def http2(): JOutgoingConnectionBuilder = new JavaAdapter(actual.http2().asInstanceOf[Impl])

    override def https1(): JOutgoingConnectionBuilder = new JavaAdapter(actual.https1().asInstanceOf[Impl])

    override def insecureHttp1(): JOutgoingConnectionBuilder = new JavaAdapter(actual.insecureHttp1().asInstanceOf[Impl])

    override def insecureForcedHttp2(): JOutgoingConnectionBuilder = new JavaAdapter(actual.insecureForcedHttp2().asInstanceOf[Impl])

    override def withConnectionContext(context: akka.http.javadsl.ConnectionContext): JOutgoingConnectionBuilder =
      new JavaAdapter(actual.withConnectionContext(context.asInstanceOf[ConnectionContext]).asInstanceOf[Impl])

    override def withClientConnectionSettings(settings: akka.http.javadsl.settings.ClientConnectionSettings): JOutgoingConnectionBuilder =
      new JavaAdapter(actual.withClientConnectionSettings(settings.asInstanceOf[ClientConnectionSettings]).asInstanceOf[Impl])

    override def logTo(logger: LoggingAdapter): JOutgoingConnectionBuilder =
      new JavaAdapter(actual.logTo(logger).asInstanceOf[Impl])

    override def connectionFlow(): JFlow[javadsl.model.HttpRequest, javadsl.model.HttpResponse, CompletionStage[javadsl.OutgoingConnection]] = {
      import scala.compat.java8.FutureConverters.toJava
      actual.connectionFlow().asInstanceOf[Flow[javadsl.model.HttpRequest, javadsl.model.HttpResponse, Future[OutgoingConnection]]]
        .mapMaterializedValue(f => toJava(f.map(oc => new javadsl.OutgoingConnection(oc))(ExecutionContexts.parasitic)))
        .asJava[javadsl.model.HttpRequest]
    }
  }
}
