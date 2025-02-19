/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import javax.net.ssl.SSLSession

import akka.stream.TLSProtocol._

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import akka.parboiled2.CharUtils
import akka.util.ByteString
import akka.http.impl.model.parser.CharacterClasses
import akka.http.scaladsl.settings.ParserSettings
import akka.http.scaladsl.model.{ ParsingException => _, _ }
import headers._
import HttpProtocols._
import ParserOutput._
import akka.annotation.InternalApi
import akka.http.scaladsl.settings.ParserSettings.ConflictingContentTypeHeaderProcessingMode

/**
 * INTERNAL API
 *
 * Common logic for http request and response message parsing
 */
@InternalApi
private[http] trait HttpMessageParser[Output >: MessageOutput <: ParserOutput] {

  import HttpMessageParser._

  // Either:
  //   - null: currently no output
  //   - Output: one output element
  //   - ListBuffer: several output elements
  private[this] var result: AnyRef = null
  private[this] var state: ByteString => StateResult = startNewMessage(_, 0)
  private[this] var protocol: HttpProtocol = `HTTP/1.1`
  protected var completionHandling: CompletionHandling = CompletionOk
  protected var terminated = false

  private[this] var lastSession: SSLSession = null // used to prevent having to recreate header on each message
  private[this] var tlsSessionInfoHeader: `Tls-Session-Info` = null

  protected def settings: ParserSettings
  protected def headerParser: HttpHeaderParser
  protected def isResponseParser: Boolean
  /** invoked if the specified protocol is unknown */
  protected def onBadProtocol(input: ByteString): Nothing
  protected def parseMessage(input: ByteString, offset: Int): HttpMessageParser.StateResult
  protected def parseEntity(headers: List[HttpHeader], protocol: HttpProtocol, input: ByteString, bodyStart: Int,
                            clh: Option[`Content-Length`], cth: Option[`Content-Type`], isChunked: Boolean,
                            expect100continue: Boolean, hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean,
                            sslSession: SSLSession): HttpMessageParser.StateResult

  protected final def initialHeaderBuffer: ListBuffer[HttpHeader] =
    if (settings.includeTlsSessionInfoHeader && tlsSessionInfoHeader != null) new ListBuffer() += tlsSessionInfoHeader
    else new ListBuffer()

  final def parseSessionBytes(input: SessionBytes): Output = {
    if (input.session ne lastSession) {
      lastSession = input.session
      tlsSessionInfoHeader = `Tls-Session-Info`(input.session)
    }
    parseBytes(input.bytes)
  }
  final def parseBytes(input: ByteString): Output = {
    @tailrec def run(next: ByteString => StateResult): StateResult =
      (try next(input)
      catch {
        case e: ParsingException => failMessageStart(e.status, e.info)
        case NotEnoughDataException =>
          // we are missing a try/catch{continue} wrapper somewhere
          throw new IllegalStateException("unexpected NotEnoughDataException", NotEnoughDataException)
        case IllegalHeaderException(error) =>
          failMessageStart(StatusCodes.BadRequest, error)
      }) match {
        case Trampoline(x) => run(x)
        case x             => x
      }

    if (result ne null) throw new IllegalStateException("Unexpected `onPush`")
    run(state)
    doPull()
  }

  protected final def doPull(): Output =
    result match {
      case null => if (terminated) StreamEnd else NeedMoreData
      case buffer: ListBuffer[Output @unchecked] =>
        val head = buffer.head
        buffer.remove(0) // faster than `ListBuffer::drop`
        if (buffer.isEmpty) result = null
        head
      case ele: Output @unchecked =>
        result = null
        ele
      case _ => throw new IllegalArgumentException("Unexpected result") // compiler completeness check pleaser
    }

  protected final def shouldComplete(): Boolean = {
    completionHandling() match {
      case Some(x) => emit(x)
      case None    => // nothing to do
    }
    terminated = true
    result eq null
  }

  protected final def startNewMessage(input: ByteString, offset: Int): StateResult = {
    if (offset < input.length) setCompletionHandling(CompletionIsMessageStartError)
    try parseMessage(input, offset)
    catch { case NotEnoughDataException => continue(input, offset)(startNewMessage) }
  }

  protected final def parseProtocol(input: ByteString, cursor: Int): Int = {
    def c(ix: Int) = byteChar(input, cursor + ix)
    if (c(0) == 'H' && c(1) == 'T' && c(2) == 'T' && c(3) == 'P' && c(4) == '/' && c(5) == '1' && c(6) == '.') {
      protocol = c(7) match {
        case '0' => `HTTP/1.0`
        case '1' => `HTTP/1.1`
        case _   => onBadProtocol(input.drop(cursor))
      }
      cursor + 8
    } else onBadProtocol(input.drop(cursor))
  }

  /**
   * @param ch connection header
   * @param clh content-length
   * @param cth content-type
   * @param teh transfer-encoding
   * @param e100c expect 100 continue
   * @param hh host header seen
   */
  @tailrec protected final def parseHeaderLines(input: ByteString, lineStart: Int, headers: ListBuffer[HttpHeader] = initialHeaderBuffer,
                                                headerCount: Int = 0, ch: Option[Connection] = None,
                                                clh: Option[`Content-Length`] = None, cth: Option[`Content-Type`] = None,
                                                isChunked: Boolean = false, e100c: Boolean = false,
                                                hh: Boolean = false): StateResult =
    if (headerCount < settings.maxHeaderCount) {
      var lineEnd = 0
      val resultHeader =
        try {
          lineEnd = headerParser.parseHeaderLine(input, lineStart)()
          headerParser.resultHeader
        } catch {
          case NotEnoughDataException => null
        }
      resultHeader match {
        case null => continue(input, lineStart)(parseHeaderLinesAux(headers, headerCount, ch, clh, cth, isChunked, e100c, hh))

        case EmptyHeader =>
          val close = HttpMessage.connectionCloseExpected(protocol, ch)
          setCompletionHandling(CompletionIsEntityStreamError)
          parseEntity(headers.toList, protocol, input, lineEnd, clh, cth, isChunked, e100c, hh, close, lastSession)

        case h: `Content-Length` => clh match {
          case None      => parseHeaderLines(input, lineEnd, headers, headerCount + 1, ch, Some(h), cth, isChunked, e100c, hh)
          case Some(`h`) => parseHeaderLines(input, lineEnd, headers, headerCount, ch, clh, cth, isChunked, e100c, hh)
          case _         => failMessageStart("HTTP message must not contain more than one Content-Length header")
        }
        case h: `Content-Type` => cth match {
          case None =>
            parseHeaderLines(input, lineEnd, headers, headerCount + 1, ch, clh, Some(h), isChunked, e100c, hh)
          case Some(`h`) =>
            parseHeaderLines(input, lineEnd, headers, headerCount, ch, clh, cth, isChunked, e100c, hh)
          case Some(`Content-Type`(ContentTypes.`NoContentType`)) => // never encountered except when parsing conflicting headers (see below)
            parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, cth, isChunked, e100c, hh)
          case Some(x) =>
            import ConflictingContentTypeHeaderProcessingMode._
            settings.conflictingContentTypeHeaderProcessingMode match {
              case Error         => failMessageStart("HTTP message must not contain more than one Content-Type header")
              case First         => parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, cth, isChunked, e100c, hh)
              case Last          => parseHeaderLines(input, lineEnd, headers += x, headerCount + 1, ch, clh, Some(h), isChunked, e100c, hh)
              case NoContentType => parseHeaderLines(input, lineEnd, headers += x += h, headerCount + 1, ch, clh, Some(`Content-Type`(ContentTypes.`NoContentType`)), isChunked, e100c, hh)
            }
        }
        case h: `Transfer-Encoding` =>
          if (!isChunked) {
            h.encodings match {
              case Seq(TransferEncodings.chunked) =>
                // A single chunked is the only one we support
                parseHeaderLines(input, lineEnd, headers, headerCount + 1, ch, clh, cth, isChunked = true, e100c, hh)
              case Seq(unknown) =>
                failMessageStart(s"Unsupported Transfer-Encoding '${unknown.name}'")
              case _ =>
                failMessageStart("Multiple Transfer-Encoding entries not supported")

            }
          } else {
            // only allow one 'chunked'
            failMessageStart("Multiple Transfer-Encoding entries not supported")
          }
        case h: Connection => ch match {
          case None    => parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, Some(h), clh, cth, isChunked, e100c, hh)
          case Some(x) => parseHeaderLines(input, lineEnd, headers, headerCount, Some(x append h.tokens), clh, cth, isChunked, e100c, hh)
        }
        case h: Host =>
          if (!hh || isResponseParser) parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, cth, isChunked, e100c, hh = true)
          else failMessageStart("HTTP message must not contain more than one Host header")

        case h: Expect => parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, cth, isChunked, e100c = true, hh)

        case h         => parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, cth, isChunked, e100c, hh)
      }
    } else failMessageStart(s"HTTP message contains more than the configured limit of ${settings.maxHeaderCount} headers")

  // work-around for compiler complaining about non-tail-recursion if we inline this method
  private def parseHeaderLinesAux(headers: ListBuffer[HttpHeader], headerCount: Int, ch: Option[Connection],
                                  clh: Option[`Content-Length`], cth: Option[`Content-Type`], isChunked: Boolean,
                                  e100c: Boolean, hh: Boolean)(input: ByteString, lineStart: Int): StateResult =
    parseHeaderLines(input, lineStart, headers, headerCount, ch, clh, cth, isChunked, e100c, hh)

  protected final def parseFixedLengthBody(
    remainingBodyBytes: Long,
    isLastMessage:      Boolean)(input: ByteString, bodyStart: Int): StateResult = {
    val remainingInputBytes = input.length - bodyStart
    if (remainingInputBytes > 0) {
      if (remainingInputBytes < remainingBodyBytes) {
        emit(EntityPart(input.drop(bodyStart).compact))
        continue(parseFixedLengthBody(remainingBodyBytes - remainingInputBytes, isLastMessage))
      } else {
        val offset = bodyStart + remainingBodyBytes.toInt
        emit(EntityPart(input.slice(bodyStart, offset).compact))
        emit(MessageEnd)
        setCompletionHandling(CompletionOk)
        if (isLastMessage) terminate()
        else startNewMessage(input, offset)
      }
    } else continue(input, bodyStart)(parseFixedLengthBody(remainingBodyBytes, isLastMessage))
  }

  protected final def parseChunk(input: ByteString, offset: Int, isLastMessage: Boolean, totalBytesRead: Long): StateResult = {
    @tailrec def parseTrailer(extension: String, lineStart: Int, headers: List[HttpHeader] = Nil,
                              headerCount: Int = 0): StateResult = {
      var errorInfo: ErrorInfo = null
      val lineEnd =
        try headerParser.parseHeaderLine(input, lineStart)()
        catch { case e: ParsingException => errorInfo = e.info; 0 }
      if (errorInfo eq null) {
        headerParser.resultHeader match {
          case EmptyHeader =>
            val lastChunk =
              if (extension.isEmpty && headers.isEmpty) HttpEntity.LastChunk else HttpEntity.LastChunk(extension, headers)
            emit(EntityChunk(lastChunk))
            emit(MessageEnd)
            setCompletionHandling(CompletionOk)
            if (isLastMessage) terminate()
            else startNewMessage(input, lineEnd)
          case header if headerCount < settings.maxHeaderCount =>
            parseTrailer(extension, lineEnd, header :: headers, headerCount + 1)
          case _ => failEntityStream(s"Chunk trailer contains more than the configured limit of ${settings.maxHeaderCount} headers")
        }
      } else failEntityStream(errorInfo)
    }

    def parseChunkBody(chunkSize: Int, extension: String, cursor: Int): StateResult =
      if (chunkSize > 0) {
        val chunkBodyEnd = cursor + chunkSize
        def result(terminatorLen: Int) = {
          emit(EntityChunk(HttpEntity.Chunk(input.slice(cursor, chunkBodyEnd).compact, extension)))
          Trampoline(_ => parseChunk(input, chunkBodyEnd + terminatorLen, isLastMessage, totalBytesRead + chunkSize))
        }
        byteChar(input, chunkBodyEnd) match {
          case '\r' if byteChar(input, chunkBodyEnd + 1) == '\n' => result(2)
          case '\n' => result(1)
          case x => failEntityStream("Illegal chunk termination")
        }
      } else parseTrailer(extension, cursor)

    @tailrec def parseChunkExtensions(chunkSize: Int, cursor: Int)(startIx: Int = cursor): StateResult =
      if (cursor - startIx <= settings.maxChunkExtLength) {
        def extension = asciiString(input, startIx, cursor)
        byteChar(input, cursor) match {
          case '\r' if byteChar(input, cursor + 1) == '\n' => parseChunkBody(chunkSize, extension, cursor + 2)
          case '\n' => parseChunkBody(chunkSize, extension, cursor + 1)
          case _ => parseChunkExtensions(chunkSize, cursor + 1)(startIx)
        }
      } else failEntityStream(s"HTTP chunk extension length exceeds configured limit of ${settings.maxChunkExtLength} characters")

    @tailrec def parseSize(cursor: Int, size: Long): StateResult =
      if (size <= settings.maxChunkSize) {
        byteChar(input, cursor) match {
          case c if CharacterClasses.HEXDIG(c) => parseSize(cursor + 1, size * 16 + CharUtils.hexValue(c))
          case ';' if cursor > offset => parseChunkExtensions(size.toInt, cursor + 1)()
          case '\r' if cursor > offset && byteChar(input, cursor + 1) == '\n' => parseChunkBody(size.toInt, "", cursor + 2)
          case '\n' if cursor > offset => parseChunkBody(size.toInt, "", cursor + 1)
          case c if CharacterClasses.WSP(c) => parseSize(cursor + 1, size) // illegal according to the spec but can happen, see issue #1812
          case c => failEntityStream(s"Illegal character '${escape(c)}' in chunk start")
        }
      } else failEntityStream(s"HTTP chunk size exceeds the configured limit of ${settings.maxChunkSize} bytes")

    try parseSize(offset, 0)
    catch {
      case NotEnoughDataException => continue(input, offset)(parseChunk(_, _, isLastMessage, totalBytesRead))
    }
  }

  protected def emit(output: Output): Unit = result match {
    case null                                  => result = output
    case buffer: ListBuffer[Output @unchecked] => buffer += output
    case old: Output @unchecked =>
      val buffer = new ListBuffer[Output]
      buffer += old
      buffer += output
      result = buffer
    case _ => throw new IllegalArgumentException("Unexpected output") // compiler completeness check pleaser
  }

  protected final def continue(input: ByteString, offset: Int)(next: (ByteString, Int) => StateResult): StateResult = {
    state =
      math.signum(offset - input.length) match {
        case -1 =>
          val remaining = input.drop(offset)
          more => next(remaining ++ more, 0)
        case 0 => next(_, 0)
        case 1 => throw new IllegalStateException
      }
    done()
  }

  protected final def continue(next: (ByteString, Int) => StateResult): StateResult = {
    state = next(_, 0)
    done()
  }

  protected final def failMessageStart(summary: String): StateResult = failMessageStart(summary, "")
  protected final def failMessageStart(summary: String, detail: String): StateResult = failMessageStart(StatusCodes.BadRequest, summary, detail)
  protected final def failMessageStart(status: StatusCode): StateResult = failMessageStart(status, status.defaultMessage)
  protected final def failMessageStart(status: StatusCode, summary: String, detail: String = ""): StateResult = failMessageStart(status, ErrorInfo(summary, detail))
  protected final def failMessageStart(status: StatusCode, info: ErrorInfo): StateResult = {
    emit(MessageStartError(status, info))
    setCompletionHandling(CompletionOk)
    terminate()
  }

  protected final def failEntityStream(summary: String): StateResult = failEntityStream(summary, "")
  protected final def failEntityStream(summary: String, detail: String): StateResult = failEntityStream(ErrorInfo(summary, detail))
  protected final def failEntityStream(info: ErrorInfo): StateResult = {
    emit(EntityStreamError(info))
    setCompletionHandling(CompletionOk)
    terminate()
  }

  protected final def terminate(): StateResult = {
    terminated = true
    done()
  }

  /**
   * Use [[continue]] or [[terminate]] to suspend or terminate processing.
   * Do not call this directly.
   */
  private def done(): StateResult = null // StateResult is a phantom type

  protected final def contentType(cth: Option[`Content-Type`]) = cth match {
    case Some(x) => x.contentType
    case None    => ContentTypes.`application/octet-stream`
  }

  protected final def emptyEntity(cth: Option[`Content-Type`]): StrictEntityCreator[Output, UniversalEntity] =
    StrictEntityCreator(if (cth.isDefined) HttpEntity.empty(cth.get.contentType) else HttpEntity.Empty)

  protected final def strictEntity(cth: Option[`Content-Type`], input: ByteString, bodyStart: Int,
                                   contentLength: Int): StrictEntityCreator[Output, UniversalEntity] =
    StrictEntityCreator(HttpEntity.Strict(contentType(cth), input.slice(bodyStart, bodyStart + contentLength)))

  protected final def defaultEntity[A <: ParserOutput](cth: Option[`Content-Type`], contentLength: Long) =
    StreamedEntityCreator[A, UniversalEntity] { entityParts =>
      val data = entityParts.collect {
        case EntityPart(bytes)       => bytes
        case EntityStreamError(info) => throw EntityStreamException(info)
      }
      HttpEntity.Default(contentType(cth), contentLength, data)
    }

  protected final def chunkedEntity[A <: ParserOutput](cth: Option[`Content-Type`]) =
    StreamedEntityCreator[A, RequestEntity] { entityChunks =>
      val chunks = entityChunks.collect {
        case EntityChunk(chunk)      => chunk
        case EntityStreamError(info) => throw EntityStreamException(info)
      }
      HttpEntity.Chunked(contentType(cth), chunks)
    }

  protected final def setCompletionHandling(completionHandling: CompletionHandling): Unit =
    this.completionHandling = completionHandling

}

/**
 * INTERNAL API
 */
@InternalApi
private[http] object HttpMessageParser {
  sealed trait StateResult // phantom type for ensuring soundness of our parsing method setup
  final case class Trampoline(f: ByteString => StateResult) extends StateResult

  type CompletionHandling = () => Option[ErrorOutput]
  val CompletionOk: CompletionHandling = () => None
  val CompletionIsMessageStartError: CompletionHandling =
    () => Some(ParserOutput.MessageStartError(StatusCodes.BadRequest, ErrorInfo("Illegal HTTP message start")))
  val CompletionIsEntityStreamError: CompletionHandling =
    () => Some(ParserOutput.EntityStreamError(ErrorInfo(
      "Entity stream truncation. The HTTP parser was receiving an entity when the underlying connection was " +
        "closed unexpectedly.")))
}
