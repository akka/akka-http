/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.scaladsl.model.ws._

import scala.concurrent.{ Future, Promise }
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream.stage._
import akka.stream._
import akka.stream.TLSProtocol._
import akka.stream.scaladsl._
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpEntity, HttpMethods, HttpResponse }
import akka.http.scaladsl.model.headers.Host
import akka.http.impl.engine.parsing.HttpMessageParser.StateResult
import akka.http.impl.engine.parsing.ParserOutput.{ MessageStartError, NeedMoreData, RemainingBytes, ResponseStart }
import akka.http.impl.engine.parsing.{ HttpHeaderParser, HttpResponseParser, ParserOutput }
import akka.http.impl.engine.rendering.{ HttpRequestRendererFactory, RequestRenderingContext }
import akka.http.impl.engine.ws.Handshake.Client.NegotiatedWebSocketSettings
import akka.http.impl.util.LogByteStringTools
import akka.http.impl.util.{ SingletonException, StreamUtils }
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage

import scala.collection.immutable

/** INTERNAL API */
@InternalApi
private[http] object WebSocketClientBlueprint {
  /**
   * Returns a WebSocketClientLayer that can be materialized once.
   */
  def apply(
    request:  WebSocketRequest,
    settings: ClientConnectionSettings,
    log:      LoggingAdapter): Http.WebSocketClientLayer =
    LogByteStringTools.logTLSBidiBySetting("client-plain-text", settings.logUnencryptedNetworkBytes).reversed
      .atop(simpleTls)
      .atopMat(handshake(request, settings, log))(Keep.right)
      .atop(WebSocket.framing)
      .atop(WebSocket.stack(serverSide = false, settings.websocketSettings, log = log))
      .reversed

  /**
   * A bidi flow that injects and inspects the WS handshake and then goes out of the way. This BidiFlow
   * can only be materialized once.
   */
  def handshake(
    request:  WebSocketRequest,
    settings: ClientConnectionSettings,
    log:      LoggingAdapter): BidiFlow[ByteString, ByteString, ByteString, ByteString, Future[WebSocketUpgradeResponse]] = {
    import request._
    val result = Promise[WebSocketUpgradeResponse]()

    val valve = StreamUtils.OneTimeValve()

    val subprotocols: immutable.Seq[String] = subprotocol.toList.flatMap(_.split(",")).map(_.trim)
    val (initialRequest, key) = Handshake.Client.buildRequest(uri, extraHeaders, subprotocols, settings.websocketRandomFactory())
    val hostHeader = Host(uri.authority.normalizedFor(uri.scheme))
    val renderedInitialRequest =
      HttpRequestRendererFactory.renderStrict(RequestRenderingContext(initialRequest, hostHeader), settings, log)

    class UpgradeStage extends SimpleLinearGraphStage[ByteString] {

      override def createLogic(attributes: Attributes): GraphStageLogic =
        new GraphStageLogic(shape) with InHandler with OutHandler {
          // a special version of the parser which only parses one message and then reports the remaining data
          // if some is available
          val parser: HttpResponseParser = new HttpResponseParser(settings.parserSettings, HttpHeaderParser(settings.parserSettings, log)) {
            var first = true
            override def handleInformationalResponses = false
            override protected def parseMessage(input: ByteString, offset: Int): StateResult = {
              if (first) {
                try {
                  // If we're called recursively then that's a next message
                  first = false
                  super.parseMessage(input, offset)
                } catch {
                  // Specifically NotEnoughDataException, but that's not visible here
                  case t: SingletonException => {
                    // If parsing the first message fails, retry and treat it like the first message again.
                    first = true
                    throw t
                  }
                }
              } else {
                emit(RemainingBytes(input.drop(offset)))
                terminate()
              }
            }
          }
          parser.setContextForNextResponse(HttpResponseParser.ResponseContext(HttpMethods.GET, None))

          override def onPush(): Unit = {
            parser.parseBytes(grab(in)) match {
              case NeedMoreData => pull(in)
              case ResponseStart(status, protocol, attributes, headers, entity, close) =>
                val response = new HttpResponse(status, headers, attributes, HttpEntity.Empty, protocol)
                Handshake.Client.validateResponse(response, subprotocols, key) match {
                  case Right(NegotiatedWebSocketSettings(protocol)) =>
                    result.success(ValidUpgrade(response, protocol))

                    setHandler(in, new InHandler {
                      override def onPush(): Unit = push(out, grab(in))
                    })
                    valve.open()

                    val parseResult = parser.onPull()
                    require(parseResult == ParserOutput.MessageEnd, s"parseResult should be MessageEnd but was $parseResult")
                    parser.onPull() match {
                      case NeedMoreData          => pull(in)
                      case RemainingBytes(bytes) => push(out, bytes)
                      case other =>
                        throw new IllegalStateException(s"unexpected element of type ${other.getClass}")
                    }
                  case Left(problem) =>
                    result.success(InvalidUpgradeResponse(response, s"WebSocket server at $uri returned $problem"))
                    failStage(new IllegalArgumentException(s"WebSocket upgrade did not finish because of '$problem'"))
                }
              case MessageStartError(statusCode, errorInfo) =>
                throw new IllegalStateException(s"Message failed with status code $statusCode; Error info: $errorInfo")
              case other =>
                throw new IllegalStateException(s"unexpected element of type ${other.getClass}")
            }
          }

          override def onPull(): Unit = pull(in)

          setHandlers(in, out, this)

          override def onUpstreamFailure(ex: Throwable): Unit = {
            result.tryFailure(new RuntimeException("Connection failed.", ex))
            super.onUpstreamFailure(ex)
          }
        }

      override def toString = "UpgradeStage"
    }

    BidiFlow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val networkIn = b.add(Flow[ByteString].via(new UpgradeStage))
      val wsIn = b.add(Flow[ByteString])

      val handshakeRequestSource = b.add(Source.single(renderedInitialRequest) ++ valve.source)
      val httpRequestBytesAndThenWSBytes = b.add(Concat[ByteString]())

      handshakeRequestSource ~> httpRequestBytesAndThenWSBytes
      wsIn.outlet ~> httpRequestBytesAndThenWSBytes

      BidiShape(
        networkIn.in,
        networkIn.out,
        wsIn.in,
        httpRequestBytesAndThenWSBytes.out)
    }) mapMaterializedValue (_ => result.future)
  }

  def simpleTls: BidiFlow[SslTlsInbound, ByteString, ByteString, SendBytes, NotUsed] =
    BidiFlow.fromFlowsMat(
      Flow[SslTlsInbound].collect { case SessionBytes(_, bytes) => bytes },
      Flow[ByteString].map(SendBytes(_)))(Keep.none)
}
