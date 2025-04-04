/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import java.lang.{ StringBuilder => JStringBuilder }
import javax.net.ssl.SSLSession

import scala.annotation.{ switch, tailrec }
import akka.http.scaladsl.settings.{ ParserSettings, WebSocketSettings }
import akka.util.{ ByteString, OptionVal }
import akka.http.impl.engine.ws.Handshake
import akka.http.impl.model.parser.{ CharacterClasses, UriParser }
import akka.http.scaladsl.model.{ ParsingException => _, _ }
import headers._
import StatusCodes._
import ParserOutput._
import akka.annotation.InternalApi
import akka.http.impl.engine.server.HttpAttributes
import akka.http.impl.util.ByteStringParserInput
import akka.parboiled2.ParserInput
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.TLSProtocol.SessionBytes
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

/**
 * INTERNAL API
 */
@InternalApi
private[http] final class HttpRequestParser(
  settings:            ParserSettings,
  websocketSettings:   WebSocketSettings,
  rawRequestUriHeader: Boolean,
  headerParser:        HttpHeaderParser)
  extends GraphStage[FlowShape[SessionBytes, RequestOutput]] { self =>

  import settings._

  val in = Inlet[SessionBytes]("HttpRequestParser.in")
  val out = Outlet[RequestOutput]("HttpRequestParser.out")

  val shape = FlowShape.of(in, out)

  override protected def initialAttributes: Attributes = Attributes.name("HttpRequestParser")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with HttpMessageParser[RequestOutput] with InHandler with OutHandler {

    import HttpMessageParser._

    override val settings = self.settings
    override val headerParser = self.headerParser.createShallowCopy()
    override val isResponseParser = false

    private[this] var method: HttpMethod = _
    private[this] var uri: Uri = _
    private[this] var uriBytes: ByteString = _

    override def onPush(): Unit = handleParserOutput(parseSessionBytes(grab(in)))
    override def onPull(): Unit = handleParserOutput(doPull())

    override def onUpstreamFinish(): Unit =
      if (super.shouldComplete()) completeStage()
      else if (isAvailable(out)) handleParserOutput(doPull())

    setHandlers(in, out, this)

    private def handleParserOutput(output: RequestOutput): Unit = {
      output match {
        case StreamEnd    => completeStage()
        case NeedMoreData => pull(in)
        case x            => push(out, x)
      }
    }

    override def parseMessage(input: ByteString, offset: Int): StateResult =
      if (offset < input.length) {
        var cursor = parseMethod(input, offset)
        cursor = parseRequestTarget(input, cursor)
        cursor = parseProtocol(input, cursor)
        if (byteChar(input, cursor) == '\r' && byteChar(input, cursor + 1) == '\n')
          parseHeaderLines(input, cursor + 2)
        else if (byteChar(input, cursor) == '\n')
          parseHeaderLines(input, cursor + 1)
        else onBadProtocol(input.drop(cursor))
      } else
        // Without HTTP pipelining it's likely that buffer is exhausted after reading one message,
        // so we check above explicitly if we are done and stop work here without running into NotEnoughDataException
        // when continuing to parse.
        continue(startNewMessage)

    def parseMethod(input: ByteString, cursor: Int): Int = {
      @tailrec def parseCustomMethod(ix: Int = 0, sb: JStringBuilder = new JStringBuilder(16)): Int =
        if (ix < maxMethodLength) {
          byteChar(input, cursor + ix) match {
            case ' ' =>
              customMethods(sb.toString) match {
                case Some(m) =>
                  method = m
                  cursor + ix + 1
                case None => throw new ParsingException(NotImplemented, ErrorInfo("Unsupported HTTP method", sb.toString))
              }
            case c => parseCustomMethod(ix + 1, sb.append(c))
          }
        } else
          throw new ParsingException(
            BadRequest,
            ErrorInfo("Unsupported HTTP method", s"HTTP method too long (started with '${sb.toString}')$remoteAddressStr. " +
              "Increase `akka.http.server.parsing.max-method-length` to support HTTP methods with more characters."))

      @tailrec def parseMethod(meth: HttpMethod, ix: Int = 1): Int =
        if (ix == meth.value.length)
          if (byteChar(input, cursor + ix) == ' ') {
            method = meth
            cursor + ix + 1
          } else parseCustomMethod()
        else if (byteChar(input, cursor + ix) == meth.value.charAt(ix)) parseMethod(meth, ix + 1)
        else parseCustomMethod()

      import HttpMethods._
      (byteChar(input, cursor): @switch) match {
        case 'G' => parseMethod(GET)
        case 'P' => byteChar(input, cursor + 1) match {
          case 'O' => parseMethod(POST, 2)
          case 'U' => parseMethod(PUT, 2)
          case 'A' => parseMethod(PATCH, 2)
          case _   => parseCustomMethod()
        }
        case 'D' => parseMethod(DELETE)
        case 'H' => parseMethod(HEAD)
        case 'O' => parseMethod(OPTIONS)
        case 'T' => parseMethod(TRACE)
        case 'C' => parseMethod(CONNECT)
        case 0x16 =>
          throw new ParsingException(
            BadRequest,
            ErrorInfo(
              "Unsupported HTTP method",
              s"The HTTP method started with 0x16 rather than any known HTTP method$remoteAddressStr. " +
                "Perhaps this was an HTTPS request sent to an HTTP endpoint?"))
        case _ => parseCustomMethod()
      }
    }

    val uriParser = new UriParser(null: ParserInput, uriParsingMode = uriParsingMode)

    def parseRequestTarget(input: ByteString, cursor: Int): Int = {
      val uriStart = cursor
      val uriEndLimit = cursor + maxUriLength

      @tailrec def findUriEnd(ix: Int = cursor): Int =
        if (ix == input.length) throw NotEnoughDataException
        else if (CharacterClasses.WSPCRLF(input(ix).toChar)) ix
        else if (ix < uriEndLimit) findUriEnd(ix + 1)
        else throw new ParsingException(
          UriTooLong,
          s"URI length exceeds the configured limit of $maxUriLength characters$remoteAddressStr")

      val uriEnd = findUriEnd()
      try {
        uriBytes = input.slice(uriStart, uriEnd)
        uriParser.reset(new ByteStringParserInput(uriBytes))
        uri = uriParser.parseHttpRequestTarget()
      } catch {
        case IllegalUriException(info) => throw new ParsingException(BadRequest, info)
      }
      uriEnd + 1
    }

    override def onBadProtocol(input: ByteString): Nothing = throw new ParsingException(HttpVersionNotSupported, "")

    // http://tools.ietf.org/html/rfc7230#section-3.3
    override def parseEntity(headers: List[HttpHeader], protocol: HttpProtocol, input: ByteString, bodyStart: Int,
                             clh: Option[`Content-Length`], cth: Option[`Content-Type`], isChunked: Boolean,
                             expect100continue: Boolean, hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean,
                             sslSession: SSLSession): StateResult =
      if (hostHeaderPresent || protocol == HttpProtocols.`HTTP/1.0`) {
        def emitRequestStart(
          createEntity: EntityCreator[RequestOutput, RequestEntity],
          headers:      List[HttpHeader]                            = headers) = {
          val allHeaders0 =
            if (rawRequestUriHeader) `Raw-Request-URI`(uriBytes.decodeString(HttpCharsets.`US-ASCII`.nioCharset)) :: headers
            else headers

          val attributes: Map[AttributeKey[_], Any] =
            if (settings.includeSslSessionAttribute) Map(AttributeKeys.sslSession -> SslSessionInfo(sslSession))
            else Map.empty

          val requestStart =
            if (method == HttpMethods.GET) {
              Handshake.Server.websocketUpgrade(headers, hostHeaderPresent, websocketSettings, headerParser.log) match {
                case OptionVal.Some(upgrade) =>
                  RequestStart(method, uri, protocol, attributes.updated(AttributeKeys.webSocketUpgrade, upgrade), upgrade :: allHeaders0, createEntity, expect100continue, closeAfterResponseCompletion)
                case _ => // OptionVal.None
                  RequestStart(method, uri, protocol, attributes, allHeaders0, createEntity, expect100continue, closeAfterResponseCompletion)
              }
            } else RequestStart(method, uri, protocol, attributes, allHeaders0, createEntity, expect100continue, closeAfterResponseCompletion)

          emit(requestStart)
        }

        if (!isChunked) {
          val contentLength = clh match {
            case Some(`Content-Length`(len)) => len
            case None                        => 0
          }
          if (contentLength == 0) {
            emitRequestStart(emptyEntity(cth))
            setCompletionHandling(HttpMessageParser.CompletionOk)
            startNewMessage(input, bodyStart)
          } else if (!method.isEntityAccepted) {
            failMessageStart(UnprocessableContent, s"${method.name} requests must not have an entity")
          } else if (contentLength <= input.size - bodyStart) {
            val cl = contentLength.toInt
            emitRequestStart(strictEntity(cth, input, bodyStart, cl))
            setCompletionHandling(HttpMessageParser.CompletionOk)
            startNewMessage(input, bodyStart + cl)
          } else {
            emitRequestStart(defaultEntity(cth, contentLength))
            parseFixedLengthBody(contentLength, closeAfterResponseCompletion)(input, bodyStart)
          }
        } else {
          if (!method.isEntityAccepted) {
            failMessageStart(UnprocessableContent, s"${method.name} requests must not have an entity")
          } else {
            if (clh.isEmpty) {
              emitRequestStart(chunkedEntity(cth), headers)
              parseChunk(input, bodyStart, closeAfterResponseCompletion, totalBytesRead = 0L)
            } else failMessageStart("A chunked request must not contain a Content-Length header")
          }
        }
      } else failMessageStart("Request is missing required `Host` header")

    private def remoteAddressStr: String =
      inheritedAttributes.get[HttpAttributes.RemoteAddress].map(_.address) match {
        case Some(addr) => s" from ${addr.getHostString}:${addr.getPort}"
        case None       => ""
      }
  }

  override def toString: String = "HttpRequestParser"
}
