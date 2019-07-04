/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.impl.engine.server.HttpAttributes
import akka.stream.TLSProtocol.{ SessionBytes, SessionTruncated, SslTlsInbound, SslTlsOutbound }
import akka.stream._
import akka.stream.scaladsl.Flow
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import javax.net.ssl.SSLException

/** INTERNAL API */
@InternalApi
private[http] object ProtocolSwitch {
  type HttpServerFlow = Flow[SslTlsInbound, SslTlsOutbound, NotUsed]

  def apply(
    chosenProtocolAccessor: SessionBytes => String,
    http1Stack:             HttpServerFlow,
    http2Stack:             HttpServerFlow): HttpServerFlow =
    Flow.fromGraph(
      new GraphStage[FlowShape[SslTlsInbound, SslTlsOutbound]] {

        // --- outer ports ---
        val netIn = Inlet[SslTlsInbound]("AlpnSwitch.netIn")
        val netOut = Outlet[SslTlsOutbound]("AlpnSwitch.netOut")
        // --- end of outer ports ---

        val shape: FlowShape[SslTlsInbound, SslTlsOutbound] =
          FlowShape(netIn, netOut)

        def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          logic =>

          // --- inner ports, bound to actual server in install call ---
          val serverDataIn = new SubSinkInlet[SslTlsOutbound]("ServerImpl.netIn")
          val serverDataOut = new SubSourceOutlet[SslTlsInbound]("ServerImpl.netOut")
          // --- end of inner ports ---

          override def preStart(): Unit = pull(netIn)

          setHandler(netIn, new InHandler {
            def onPush(): Unit =
              grab(netIn) match {
                case first @ SessionBytes(session, bytes) =>
                  val chosen = chosenProtocolAccessor(first)
                  chosen match {
                    case "h2" => install(http2Stack.addAttributes(HttpAttributes.tlsSessionInfo(session)), first)
                    case _    => install(http1Stack, first)
                  }
                case SessionTruncated => failStage(new SSLException("TLS session was truncated (probably missing a close_notify packet)."))
              }
          })

          private val ignorePull = new OutHandler { def onPull(): Unit = () }

          setHandler(netOut, ignorePull)

          def install(serverImplementation: HttpServerFlow, firstElement: SslTlsInbound): Unit = {
            val networkSide = Flow.fromSinkAndSource(serverDataIn.sink, serverDataOut.source)

            connect(netIn, serverDataOut, Some(firstElement))

            connect(serverDataIn, netOut)

            serverImplementation
              .addAttributes(inheritedAttributes) // propagate attributes to "real" server (such as HttpAttributes)
              .join(networkSide)
              .run()(interpreter.subFusingMaterializer)
          }

          // helpers to connect inlets and outlets also binding completion signals of given ports
          def connect[T](in: Inlet[T], out: SubSourceOutlet[T], initialElement: Option[T]): Unit = {
            val propagatePull =
              new OutHandler {
                override def onPull(): Unit = pull(in)
              }

            val firstHandler =
              initialElement match {
                case Some(ele) if out.isAvailable =>
                  out.push(ele)
                  propagatePull
                case Some(ele) =>
                  new OutHandler {
                    override def onPull(): Unit = {
                      out.push(initialElement.get)
                      out.setHandler(propagatePull)
                    }
                  }
                case None => propagatePull
              }

            out.setHandler(firstHandler)
            setHandler(in, new InHandler {
              override def onPush(): Unit = out.push(grab(in))

              override def onUpstreamFinish(): Unit = {
                out.complete()
                super.onUpstreamFinish()
              }

              override def onUpstreamFailure(ex: Throwable): Unit = {
                out.fail(ex)
                super.onUpstreamFailure(ex)
              }
            })

            if (out.isAvailable) pull(in) // to account for lost pulls during initialization
          }
          def connect[T](in: SubSinkInlet[T], out: Outlet[T]): Unit = {
            val handler = new InHandler {
              override def onPush(): Unit = push(out, in.grab())
            }

            val outHandler = new OutHandler {
              override def onPull(): Unit = in.pull()
              override def onDownstreamFinish(): Unit = {
                in.cancel()
                super.onDownstreamFinish()
              }
            }
            in.setHandler(handler)
            setHandler(out, outHandler)

            if (isAvailable(out)) in.pull() // to account for lost pulls during initialization
          }
        }
      }
    )

  def byPreface(http1Stack: HttpServerFlow, http2Stack: HttpServerFlow): HttpServerFlow = {
    def chooseProtocol(sessionBytes: SessionBytes): String =
      if (sessionBytes.bytes.startsWith(Http2Protocol.ClientConnectionPreface)) "h2" else "http/1.1"
    ProtocolSwitch(chooseProtocol, http1Stack, http2Stack)
  }
}
