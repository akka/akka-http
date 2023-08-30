/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.actor.{ ActorSystem, ClassicActorSystemProvider, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.event.LoggingAdapter
import akka.http.impl.engine.HttpConnectionIdleTimeoutBidi
import akka.http.impl.engine.server.{ GracefulTerminatorStage, MasterServerTerminator, ServerTerminator, UpgradeToOtherProtocolResponseHeader }
import akka.http.impl.util.LogByteStringTools
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.{ ConnectionContext, Http, HttpsConnectionContext }
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Connection, RawHeader, Upgrade, UpgradeProtocol }
import akka.http.scaladsl.model.http2.Http2SettingsHeader
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.Attributes
import akka.stream.TLSClosing
import akka.stream.TLSProtocol.{ SslTlsInbound, SslTlsOutbound }
import akka.stream.impl.io.TlsUtils
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source, TLS, TLSPlacebo, Tcp }
import akka.stream.{ IgnoreComplete, Materializer }
import akka.util.ByteString
import akka.Done

import javax.net.ssl.SSLEngine
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

/**
 * INTERNAL API
 *
 * Internal entry points for Http/2 server
 */
@InternalApi
private[http] final class Http2Ext(implicit val system: ActorSystem)
  extends akka.actor.Extension {
  // FIXME: won't having the same package as top-level break osgi?

  import Http2._

  private[this] final val DefaultPortForProtocol = -1 // any negative value

  val http = Http(system)
  val telemetry = TelemetrySpi.create(system)

  // TODO: split up similarly to what `Http` does into `serverLayer`, `bindAndHandle`, etc.
  def bindAndHandleAsync(
    handler:   HttpRequest => Future[HttpResponse],
    interface: String, port: Int = DefaultPortForProtocol,
    connectionContext: ConnectionContext,
    settings:          ServerSettings    = ServerSettings(system),
    log:               LoggingAdapter    = system.log)(implicit fm: Materializer): Future[ServerBinding] = {

    val httpPlusSwitching: HttpPlusSwitching =
      if (connectionContext.isSecure) httpsWithAlpn(connectionContext.asInstanceOf[HttpsConnectionContext])
      else priorKnowledge

    val effectivePort =
      if (port >= 0) port
      else if (connectionContext.isSecure) settings.defaultHttpsPort
      else settings.defaultHttpPort

    val handlerWithErrorHandling = withErrorHandling(log, handler)

    val http1: HttpImplementation =
      Flow[HttpRequest].mapAsync(settings.pipeliningLimit)(handleUpgradeRequests(handlerWithErrorHandling, settings, log))
        .joinMat(GracefulTerminatorStage(system, settings) atop http.serverLayer(settings, log = log))(Keep.right)
    val http2: HttpImplementation =
      Http2Blueprint.handleWithStreamIdHeader(settings.http2Settings.maxConcurrentStreams)(handlerWithErrorHandling)(system.dispatcher)
        .joinMat(Http2Blueprint.serverStackTls(settings, log, telemetry, Http().dateHeaderRendering))(Keep.right)

    val masterTerminator = new MasterServerTerminator(log)

    Tcp(system).bind(interface, effectivePort, settings.backlog, settings.socketOptions, halfClose = false, Duration.Inf) // we knowingly disable idle-timeout on TCP level, as we handle it explicitly in Akka HTTP itself
      .via(if (telemetry == NoOpTelemetry) Flow[Tcp.IncomingConnection] else telemetry.serverBinding)
      .mapAsyncUnordered(settings.maxConnections) {
        (incoming: Tcp.IncomingConnection) =>
          try {
            httpPlusSwitching(http1, http2).addAttributes(prepareServerAttributes(settings, incoming))
              .watchTermination() {
                case (connectionTerminatorF, future) =>
                  connectionTerminatorF.foreach { connectionTerminator =>
                    masterTerminator.registerConnection(connectionTerminator)(fm.executionContext)
                    future.onComplete(_ => masterTerminator.removeConnection(connectionTerminator))(fm.executionContext)
                  }(fm.executionContext)
                  future // drop the terminator matValue, we already registered is which is all we need to do here
              }
              .join(HttpConnectionIdleTimeoutBidi(settings.idleTimeout, Some(incoming.remoteAddress)) join incoming.flow)
              .addAttributes(Http.cancellationStrategyAttributeForDelay(settings.streamCancellationDelay))
              .run().recover {
                // Ignore incoming errors from the connection as they will cancel the binding.
                // As far as it is known currently, these errors can only happen if a TCP error bubbles up
                // from the TCP layer through the HTTP layer to the Http.IncomingConnection.flow.
                // See https://github.com/akka/akka/issues/17992
                case NonFatal(ex) =>
                  Done
              }(ExecutionContexts.parasitic)
          } catch {
            case NonFatal(e) =>
              log.error(e, "Could not materialize handling flow for {}", incoming)
              throw e
          }
      }.mapMaterializedValue {
        _.map(tcpBinding => ServerBinding(tcpBinding.localAddress)(
          () => tcpBinding.unbind(),
          timeout => masterTerminator.terminate(timeout)(fm.executionContext)
        ))(fm.executionContext)
      }.to(Sink.ignore).run()
  }

  private def withErrorHandling(log: LoggingAdapter, handler: HttpRequest => Future[HttpResponse]): HttpRequest => Future[HttpResponse] = { request =>
    try {
      handler(request).recover {
        case NonFatal(ex) => handleHandlerError(log, ex)
      }(ExecutionContexts.parasitic)
    } catch {
      case NonFatal(ex) => Future.successful(handleHandlerError(log, ex))
    }
  }

  private def handleHandlerError(log: LoggingAdapter, ex: Throwable): HttpResponse = {
    log.error(ex, "Internal server error, sending 500 response")
    HttpResponse(StatusCodes.InternalServerError)
  }

  private def prepareServerAttributes(settings: ServerSettings, incoming: Tcp.IncomingConnection) = {
    val attrs = Http.prepareAttributes(settings, incoming)
    if (telemetry == NoOpTelemetry) attrs
    else {
      // transfer attributes allowing context propagation from serverBinding interceptor to request-response interceptor
      attrs.and(incoming.flow.traversalBuilder.attributes)
    }
  }

  private def handleUpgradeRequests(
    handler:  HttpRequest => Future[HttpResponse],
    settings: ServerSettings,
    log:      LoggingAdapter
  ): HttpRequest => Future[HttpResponse] = { req =>
    req.header[Upgrade] match {
      case Some(upgrade) if upgrade.protocols.exists(_.name equalsIgnoreCase "h2c") =>

        log.debug("Got h2c upgrade request from HTTP/1.1 to HTTP2")

        // https://http2.github.io/http2-spec/#Http2SettingsHeader 3.2.1 HTTP2-Settings Header Field
        val upgradeSettings = req.headers.collect {
          case raw: RawHeader if raw.lowercaseName == Http2SettingsHeader.name =>
            Http2SettingsHeader.parse(raw.value, log)
        }

        upgradeSettings match {
          // Must be exactly one
          case immutable.Seq(Success(settingsFromHeader)) =>
            // inject the actual upgrade request with a stream identifier of 1
            // https://http2.github.io/http2-spec/#rfc.section.3.2
            val injectedRequest = Source.single(req.addAttribute(Http2.streamId, 1))

            val serverLayer: Flow[ByteString, ByteString, Future[Done]] = Flow.fromGraph(
              Flow[HttpRequest]
                .watchTermination()(Keep.right)
                .prepend(injectedRequest)
                .via(Http2Blueprint.handleWithStreamIdHeader(settings.http2Settings.maxConcurrentStreams)(handler)(system.dispatcher))
                // the settings from the header are injected into the blueprint as initial demuxer settings
                .joinMat(Http2Blueprint.serverStack(settings, log, settingsFromHeader, true, telemetry, Http().dateHeaderRendering))(Keep.left))

            Future.successful(
              HttpResponse(
                StatusCodes.SwitchingProtocols,
                immutable.Seq[HttpHeader](
                  ConnectionUpgradeHeader,
                  UpgradeHeader,
                  UpgradeToOtherProtocolResponseHeader(serverLayer)
                )
              )
            )
          case immutable.Seq(Failure(e)) =>
            log.warning("Failed to parse http2-settings header in upgrade [{}], continuing with HTTP/1.1", e.getMessage)
            handler(req)
          // A server MUST NOT upgrade the connection to HTTP/2 if this header field
          // is not present or if more than one is present
          case _ =>
            log.debug("Invalid upgrade request (http2-settings header missing or repeated)")
            handler(req)
        }

      case _ =>
        handler(req)
    }
  }
  val ConnectionUpgradeHeader = Connection(List("upgrade"))
  val UpgradeHeader = Upgrade(List(UpgradeProtocol("h2c")))

  def httpsWithAlpn(httpsContext: HttpsConnectionContext)(http1: HttpImplementation, http2: HttpImplementation): Flow[ByteString, ByteString, Future[ServerTerminator]] = {
    // Mutable cell to transport the chosen protocol from the SSLEngine to
    // the switch stage.
    // Doesn't need to be volatile because there's a happens-before relationship (enforced by memory barriers)
    // between the SSL handshake and sending out the first SessionBytes, and receiving the first SessionBytes
    // and reading out the variable.
    var chosenProtocol: Option[String] = None
    def setChosenProtocol(protocol: String): Unit =
      if (chosenProtocol.isEmpty) chosenProtocol = Some(protocol)
      else throw new IllegalStateException("ChosenProtocol was set twice. Http2.serverLayer is not reusable.")
    def getChosenProtocol(): String = chosenProtocol.getOrElse(Http2AlpnSupport.HTTP11) // default to http/1, e.g. when ALPN jar is missing

    var eng: Option[SSLEngine] = None

    def createEngine(): SSLEngine = {
      val engine = httpsContext.sslContextData(None)
      eng = Some(engine)
      engine.setUseClientMode(false)
      Http2AlpnSupport.enableForServer(engine, setChosenProtocol)
    }
    val tls = TLS(() => createEngine(), _ => Success(()), IgnoreComplete)

    ProtocolSwitch(_ => getChosenProtocol(), http1, http2) join
      tls
  }

  def outgoingConnection(host: String, port: Int, connectionContext: HttpsConnectionContext, clientConnectionSettings: ClientConnectionSettings, log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
    def createEngine(): SSLEngine = {
      val engine = connectionContext.sslContextData(Some((host, port)))
      engine.setUseClientMode(true)
      Http2AlpnSupport.clientSetApplicationProtocols(engine, Array("h2"))
      engine
    }

    val stack = Http2Blueprint.clientStack(clientConnectionSettings, log, telemetry).addAttributes(prepareClientAttributes(host, port)) atop
      Http2Blueprint.unwrapTls atop
      LogByteStringTools.logTLSBidiBySetting("client-plain-text", clientConnectionSettings.logUnencryptedNetworkBytes) atop
      TLS(createEngine _, closing = TLSClosing.eagerClose)

    stack.joinMat(clientConnectionSettings.transport.connectTo(host, port, clientConnectionSettings)(system.classicSystem))(Keep.right)
      .addAttributes(Http.cancellationStrategyAttributeForDelay(clientConnectionSettings.streamCancellationDelay))
  }

  def outgoingConnectionPriorKnowledge(host: String, port: Int, clientConnectionSettings: ClientConnectionSettings, log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
    val stack = Http2Blueprint.clientStack(clientConnectionSettings, log, telemetry).addAttributes(prepareClientAttributes(host, port)) atop
      Http2Blueprint.unwrapTls atop
      LogByteStringTools.logTLSBidiBySetting("client-plain-text", clientConnectionSettings.logUnencryptedNetworkBytes) atop
      TLSPlacebo()

    stack.joinMat(clientConnectionSettings.transport.connectTo(host, port, clientConnectionSettings)(system.classicSystem))(Keep.right)
      .addAttributes(Http.cancellationStrategyAttributeForDelay(clientConnectionSettings.streamCancellationDelay))
  }

  private def prepareClientAttributes(serverHost: String, port: Int): Attributes =
    if (telemetry == NoOpTelemetry) Attributes.none
    else TelemetryAttributes.prepareClientFlowAttributes(serverHost, port)

}

/** INTERNAL API */
@InternalApi
private[http] object Http2 extends ExtensionId[Http2Ext] with ExtensionIdProvider {

  val streamId = AttributeKey[Int]("x-http2-stream-id")

  override def get(system: ActorSystem): Http2Ext = super.get(system)
  override def get(system: ClassicActorSystemProvider): Http2Ext = super.get(system)
  def apply()(implicit system: ClassicActorSystemProvider): Http2Ext = super.apply(system)
  override def apply(system: ActorSystem): Http2Ext = super.apply(system)
  def lookup: ExtensionId[_ <: Extension] = Http2
  def createExtension(system: ExtendedActorSystem): Http2Ext = new Http2Ext()(system)

  private[http] type HttpImplementation = Flow[SslTlsInbound, SslTlsOutbound, ServerTerminator]
  private[http] type HttpPlusSwitching = (HttpImplementation, HttpImplementation) => Flow[ByteString, ByteString, Future[ServerTerminator]]

  private[http] def priorKnowledge(http1: HttpImplementation, http2: HttpImplementation): Flow[ByteString, ByteString, Future[ServerTerminator]] =
    TLSPlacebo().reversed.joinMat(
      ProtocolSwitch.byPreface(http1, http2)
    )(Keep.right)
}
