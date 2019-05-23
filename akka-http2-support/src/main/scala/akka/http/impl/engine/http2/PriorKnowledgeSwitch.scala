/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import javax.net.ssl.SSLException

import akka.util.ByteString
import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.impl.engine.server.HttpAttributes
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.TLSProtocol.{ SessionBytes, SessionTruncated, SslTlsInbound, SslTlsOutbound }
import akka.stream.scaladsl.{ BidiFlow, Flow }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream._

/** INTERNAL API */
@InternalApi
private[http] object PriorKnowledgeSwitch {
  type HttpServerBidiFlow = BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed]
  type HttpServerBidiShape = BidiShape[HttpResponse, ByteString, ByteString, HttpRequest]

  private final val PRIOR_KNOWLEDGE_PREFACE = ByteString("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")

  def apply(
    http1Stack: HttpServerBidiFlow,
    http2Stack: HttpServerBidiFlow): HttpServerBidiFlow =
    BidiFlow.fromGraph(
      new GraphStage[HttpServerBidiShape] {

        // --- outer ports ---
        val netIn = Inlet[ByteString]("PriorKnowledgeSwitch.netIn")
        val netOut = Outlet[ByteString]("PriorKnowledgeSwitch.netOut")

        val requestOut = Outlet[HttpRequest]("PriorKnowledgeSwitch.requestOut")
        val responseIn = Inlet[HttpResponse]("PriorKnowledgeSwitch.responseIn")
        // --- end of outer ports ---

        override val shape: HttpServerBidiShape =
          BidiShape(responseIn, netOut, netIn, requestOut)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          logic =>

          // --- inner ports, bound to actual server in install call ---
          val serverDataIn = new SubSinkInlet[ByteString]("ServerImpl.netIn")
          val serverDataOut = new SubSourceOutlet[ByteString]("ServerImpl.netOut")

          val serverRequestIn = new SubSinkInlet[HttpRequest]("ServerImpl.serverRequestIn")
          val serverResponseOut = new SubSourceOutlet[HttpResponse]("ServerImpl.serverResponseOut")
          // --- end of inner ports ---

          override def preStart(): Unit = pull(netIn)

          setHandler(netIn, new InHandler {
            private[this] var grabbed = ByteString.empty
            def onPush(): Unit = {
              val data = grabbed ++ grab(netIn)
              if (data.length >= PRIOR_KNOWLEDGE_PREFACE.length) { // We should know by now
                if (data.startsWith(PRIOR_KNOWLEDGE_PREFACE, 0))
                  install(http2Stack, data)
                else
                  install(http1Stack, data)
              } else if (data.isEmpty || data.startsWith(PRIOR_KNOWLEDGE_PREFACE, 0)) { // Still unknown
                grabbed = data
              } else { // Not a Prior Knowledge request
                install(http1Stack, data)
              }
            }
          })

          private val ignorePull = new OutHandler { def onPull(): Unit = () }
          private val failPush = new InHandler { def onPush(): Unit = throw new IllegalStateException("Wasn't pulled yet") }

          setHandler(netOut, ignorePull)
          setHandler(requestOut, ignorePull)
          setHandler(responseIn, failPush)

          def install(serverImplementation: HttpServerBidiFlow, firstElement: ByteString): Unit = {
            val networkSide = Flow.fromSinkAndSource(serverDataIn.sink, serverDataOut.source)
            val userSide = Flow.fromSinkAndSource(serverRequestIn.sink, serverResponseOut.source)

            connect(netIn, serverDataOut, Some(firstElement))
            connect(responseIn, serverResponseOut, None)

            connect(serverDataIn, netOut)
            connect(serverRequestIn, requestOut)

            serverImplementation
              .addAttributes(inheritedAttributes) // propagate attributes to "real" server (such as HttpAttributes)
              .join(networkSide)
              .join(userSide)
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
}
