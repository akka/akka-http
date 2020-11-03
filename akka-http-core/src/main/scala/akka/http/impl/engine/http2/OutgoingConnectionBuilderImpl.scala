/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.util.concurrent.CompletableFuture
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
import akka.http.javadsl.model
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

  def apply(host: String, system: ClassicActorSystemProvider): OutgoingConnectionBuilder =
    Impl(
      host,
      None,
      clientConnectionSettings = ClientConnectionSettings(system),
      connectionContext = None,
      log = system.classicSystem.log,
      system = system,
      usingHttp2 = false
    )

  private final case class Impl(
    host:                     String,
    port:                     Option[Int],
    clientConnectionSettings: ClientConnectionSettings,
    connectionContext:        Option[HttpsConnectionContext],
    log:                      LoggingAdapter,
    system:                   ClassicActorSystemProvider,
    usingHttp2:               Boolean) extends OutgoingConnectionBuilder {

    override def toHost(host: String): OutgoingConnectionBuilder = copy(host = host)

    override def toPort(port: Int): OutgoingConnectionBuilder = copy(port = Some(port))

    override def withCustomHttpsConnectionContext(httpsConnectionContext: HttpsConnectionContext): OutgoingConnectionBuilder = copy(connectionContext = Some(httpsConnectionContext))

    override def withClientConnectionSettings(settings: ClientConnectionSettings): OutgoingConnectionBuilder = copy(clientConnectionSettings = settings)

    override def logTo(logger: LoggingAdapter): OutgoingConnectionBuilder = copy(log = logger)

    override def http(): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
      // http/1.1 plaintext
      Http(system).outgoingConnectionUsingContext(host, port.getOrElse(80), ConnectionContext.noEncryption(), clientConnectionSettings, log)
    }

    override def https(): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
      // http/1.1 tls
      Http(system).outgoingConnectionHttps(host, port.getOrElse(443), connectionContext.getOrElse(Http(system).defaultClientHttpsContext), None, clientConnectionSettings, log)
    }

    override def http2(): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
      // http/2 tls
      val port = this.port.getOrElse(443)
      def createEngine(): SSLEngine = {
        val engine = connectionContext.getOrElse(Http(system).defaultClientHttpsContext).sslContextData match {
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

    override def http2WithPriorKnowledge(): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
      // http/2 prior knowledge plaintext
      // FIXME verify this actually works
      val port = this.port.getOrElse(80)
      val stack = Http2Blueprint.clientStack(clientConnectionSettings, log) atop
        Http2Blueprint.unwrapTls atop
        LogByteStringTools.logTLSBidiBySetting("client-plain-text", clientConnectionSettings.logUnencryptedNetworkBytes) atop
        TLSPlacebo()

      stack.joinMat(clientConnectionSettings.transport.connectTo(host, port, clientConnectionSettings)(system.classicSystem))(Keep.right)
    }

    override private[akka] def toJava: JOutgoingConnectionBuilder = new JavaAdapter(this)
  }

  private class JavaAdapter(actual: Impl) extends JOutgoingConnectionBuilder {

    override def toHost(host: String): JOutgoingConnectionBuilder = new JavaAdapter(actual.toHost(host).asInstanceOf[Impl])

    override def toPort(port: Int): JOutgoingConnectionBuilder = new JavaAdapter(actual.toPort(port).asInstanceOf[Impl])

    override def http(): JFlow[javadsl.model.HttpRequest, javadsl.model.HttpResponse, CompletionStage[javadsl.OutgoingConnection]] =
      javaFlow(actual.http())

    override def https(): JFlow[javadsl.model.HttpRequest, javadsl.model.HttpResponse, CompletionStage[javadsl.OutgoingConnection]] =
      javaFlow(actual.https())

    override def http2WithPriorKnowledge(): JFlow[javadsl.model.HttpRequest, javadsl.model.HttpResponse, CompletionStage[javadsl.OutgoingConnection]] =
      javaFlow(actual.http2WithPriorKnowledge())

    override def http2(): JFlow[javadsl.model.HttpRequest, javadsl.model.HttpResponse, CompletionStage[javadsl.OutgoingConnection]] =
      javaFlow(actual.http2())

    override def withCustomHttpsConnectionContext(httpsConnectionContext: javadsl.HttpsConnectionContext): JOutgoingConnectionBuilder = ???

    override def withClientConnectionSettings(settings: akka.http.javadsl.settings.ClientConnectionSettings): JOutgoingConnectionBuilder =
      new JavaAdapter(actual.withClientConnectionSettings(settings.asInstanceOf[ClientConnectionSettings]).asInstanceOf[Impl])

    override def logTo(logger: LoggingAdapter): JOutgoingConnectionBuilder =
      new JavaAdapter(actual.logTo(logger).asInstanceOf[Impl])

    private def javaFlow(flow: Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]]): JFlow[javadsl.model.HttpRequest, javadsl.model.HttpResponse, CompletionStage[javadsl.OutgoingConnection]] = {
      import scala.compat.java8.FutureConverters.toJava
      flow.asInstanceOf[Flow[javadsl.model.HttpRequest, javadsl.model.HttpResponse, Future[OutgoingConnection]]]
        .mapMaterializedValue(f => toJava(f.map(oc => new javadsl.OutgoingConnection(oc))(ExecutionContexts.parasitic))).asJava[javadsl.model.HttpRequest]
    }

  }
}
