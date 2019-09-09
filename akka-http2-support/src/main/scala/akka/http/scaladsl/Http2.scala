/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.dispatch.ExecutionContexts
import akka.event.LoggingAdapter
import akka.http.impl.engine.http2.{ ProtocolSwitch, Http2AlpnSupport, Http2Blueprint }
import akka.http.impl.engine.server.MasterServerTerminator
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.TLSProtocol.{ SslTlsInbound, SslTlsOutbound }
import akka.stream.scaladsl.{ BidiFlow, Flow, Keep, Sink, TLS, TLSPlacebo, Tcp }
import akka.stream.{ IgnoreComplete, Materializer }
import akka.util.ByteString
import akka.{ Done, NotUsed }
import com.typesafe.config.Config
import javax.net.ssl.SSLEngine

import scala.concurrent.Future
import scala.util.Success
import scala.util.control.NonFatal

/** Entry point for Http/2 server */
final class Http2Ext(private val config: Config)(implicit val system: ActorSystem)
  extends akka.actor.Extension {
  // FIXME: won't having the same package as top-level break osgi?

  private[this] final val DefaultPortForProtocol = -1 // any negative value

  val http = Http(system)

  // TODO: split up similarly to what `Http` does into `serverLayer`, `bindAndHandle`, etc.
  def bindAndHandleAsync(
    handler:   HttpRequest => Future[HttpResponse],
    interface: String, port: Int = DefaultPortForProtocol,
    connectionContext: ConnectionContext,
    settings:          ServerSettings    = ServerSettings(system),
    parallelism:       Int               = 1,
    log:               LoggingAdapter    = system.log)(implicit fm: Materializer): Future[ServerBinding] = {

    val httpPlusSwitching: HttpPlusSwitching =
      if (connectionContext.isSecure) httpsWithAlpn(connectionContext.asInstanceOf[HttpsConnectionContext], fm)
      else priorKnowledge

    val effectivePort =
      if (port >= 0) port
      else if (connectionContext.isSecure) settings.defaultHttpsPort
      else settings.defaultHttpPort

    val http1 = Flow[HttpRequest].mapAsync(parallelism)(handler).join(http.serverLayer(settings, None, log))
    val http2 = Http2Blueprint.handleWithStreamIdHeader(parallelism)(handler)(system.dispatcher).join(Http2Blueprint.serverStackTls(settings, log))

    val masterTerminator = new MasterServerTerminator(log)

    Tcp().bind(interface, effectivePort, settings.backlog, settings.socketOptions, halfClose = false, settings.timeouts.idleTimeout)
      .mapAsyncUnordered(settings.maxConnections) {
        incoming: Tcp.IncomingConnection =>
          try {
            httpPlusSwitching(http1, http2).addAttributes(Http.prepareAttributes(settings, incoming))
              .watchTermination()(Keep.right)
              .join(incoming.flow)
              .run().recover {
                // Ignore incoming errors from the connection as they will cancel the binding.
                // As far as it is known currently, these errors can only happen if a TCP error bubbles up
                // from the TCP layer through the HTTP layer to the Http.IncomingConnection.flow.
                // See https://github.com/akka/akka/issues/17992
                case NonFatal(ex) =>
                  Done
              }(ExecutionContexts.sameThreadExecutionContext)
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

  type HttpImplementation = Flow[SslTlsInbound, SslTlsOutbound, NotUsed]
  type HttpPlusSwitching = (HttpImplementation, HttpImplementation) => Flow[ByteString, ByteString, NotUsed]

  def priorKnowledge(http1: HttpImplementation, http2: HttpImplementation): Flow[ByteString, ByteString, NotUsed] =
    TLSPlacebo().reversed join
      ProtocolSwitch.byPreface(http1, http2)

  def httpsWithAlpn(httpsContext: HttpsConnectionContext, fm: Materializer)(http1: HttpImplementation, http2: HttpImplementation): Flow[ByteString, ByteString, NotUsed] = {
    // Mutable cell to transport the chosen protocol from the SSLEngine to
    // the switch stage.
    // Doesn't need to be volatile because there's a happens-before relationship (enforced by memory barriers)
    // between the SSL handshake and sending out the first SessionBytes, and receiving the first SessionBytes
    // and reading out the variable.
    var chosenProtocol: Option[String] = None
    def setChosenProtocol(protocol: String): Unit =
      if (chosenProtocol.isEmpty) chosenProtocol = Some(protocol)
      else throw new IllegalStateException("ChosenProtocol was set twice. Http2.serverLayer is not reusable.")
    def getChosenProtocol(): String = chosenProtocol.getOrElse("h1") // default to http/1, e.g. when ALPN jar is missing

    var eng: Option[SSLEngine] = None
    def createEngine(): SSLEngine = {
      val engine = httpsContext.sslContext.createSSLEngine()
      eng = Some(engine)
      engine.setUseClientMode(false)
      Http2AlpnSupport.applySessionParameters(engine, httpsContext.firstSession)
      Http2AlpnSupport.enableForServer(engine, setChosenProtocol)
    }
    val tls = TLS(() => createEngine, _ => Success(()), IgnoreComplete)

    val removeEngineOnTerminate: BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {
      implicit val ec = fm.executionContext
      BidiFlow.fromFlows(
        Flow[ByteString],
        Flow[ByteString]
          .watchTermination()((n, fd) => {
            fd.onComplete(_ => eng.foreach(Http2AlpnSupport.cleanupForServer))
            n
          })
      )
    }

    ProtocolSwitch(_ => getChosenProtocol(), http1, http2) join
      tls join
      removeEngineOnTerminate
  }
}

object Http2 extends ExtensionId[Http2Ext] with ExtensionIdProvider {
  override def get(system: ActorSystem): Http2Ext = super.get(system)
  def apply()(implicit system: ActorSystem): Http2Ext = super.apply(system)
  def lookup(): ExtensionId[_ <: Extension] = Http2
  def createExtension(system: ExtendedActorSystem): Http2Ext =
    new Http2Ext(system.settings.config getConfig "akka.http")(system)
}
