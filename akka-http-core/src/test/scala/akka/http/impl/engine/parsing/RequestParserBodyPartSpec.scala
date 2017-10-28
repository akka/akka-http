/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import akka.actor.ActorSystem
import akka.http.impl.engine.parsing.ParserOutput._
import akka.http.impl.util._
import akka.http.scaladsl.model.Multipart.General.Strict
import akka.http.scaladsl.model.headers.{ ContentDispositionTypes, `Content-Disposition` }
import akka.http.scaladsl.model.{ ParsingException ⇒ ModelParsingException, _ }
import akka.http.scaladsl.settings.ParserSettings
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.TLSProtocol._
import akka.stream.impl.fusing.IteratorInterpreter
import akka.stream.scaladsl._
import akka.testkit.TestKit
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.matchers.Matcher
import org.scalatest.{ BeforeAndAfterAll, FreeSpec, Matchers }

import scala.collection.immutable.{ VectorBuilder, Seq ⇒ ImmutableSeq }
import scala.concurrent.Future
import scala.concurrent.duration._

case class RequestParserBodyPartSpecMode(name: String, lineFeed: String)

// Define a trait to duplicate all tests for CRLF and LF
trait RequestParserBodyPartSpec extends FreeSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString(
    """
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING
    akka.http.parsing.max-header-value-length = 100
    akka.http.parsing.max-uri-length = 40
    akka.http.parsing.max-content-length = 4000000000""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)

  import system.dispatcher

  implicit val materializer = ActorMaterializer()
  private val defaultTimeout = 5.seconds

  val boundary = "-----123456789"

  def mode: RequestParserBodyPartSpecMode

  s"The request parsing logic should (mode: ${mode.name})" - {
    "properly parse a request with content in multipart format" in new Test {
      val boundary = "-----123456789"
      val content =
        s"""--$boundary
           |Content-Disposition: form-data; name="text"
           |
           |text default
           |--$boundary
           |Content-Disposition: form-data; name="file1"; filename="a.txt"
           |Content-Type: text/plain
           |
           |Content of a.txt.
           |--$boundary
           |Content-Disposition: form-data; name="file2"; filename="a.html"
           |Content-Type: text/html
           |
           |<!DOCTYPE html><title>Content of a.html.</title>
           |--$boundary--"""
      prep {
        s"""POST / HTTP/1.1
           |Host: x
           |User-Agent: curl/7.19.7 xyz
           |Connection: keep-alive
           |Content-Type: multipart/form-data; boundary=$boundary
           |Content-Length: ${contentLength(content)}
           |
           |${contentText(content)}"""
      } should parseTo {
        Multipart.General.Strict(
          MediaTypes.`multipart/form-data`.withBoundary(boundary),
          ImmutableSeq(
            Multipart.General.BodyPart.Strict(
              HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, ByteString("text default")),
              ImmutableSeq(`Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" → "text")))
            ),
            Multipart.General.BodyPart.Strict(
              HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, ByteString("Content of a.txt.")),
              ImmutableSeq(`Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" → "file1", "filename" → "a.txt")))
            ),
            Multipart.General.BodyPart.Strict(
              HttpEntity.Strict(ContentTypes.`text/html(UTF-8)`, ByteString("<!DOCTYPE html><title>Content of a.html.</title>")),
              ImmutableSeq(`Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" → "file2", "filename" → "a.html")))
            )
          )
        )
      }
      closeAfterResponseCompletion shouldEqual Seq(false)
    }
  }

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private class Test {
    def awaitAtMost: FiniteDuration = defaultTimeout

    var closeAfterResponseCompletion = Seq.empty[Boolean]

    class StrictEqualMultipart(val mp: Multipart.General.Strict) {
      override def equals(other: scala.Any): Boolean = other match {
        case other: StrictEqualMultipart ⇒
          this.mp.mediaType == other.mp.mediaType &&
            // _.entity.data because data is ByteString1 or ByteString1C, which are not equal
            this.mp.strictParts.map(_.entity.data) == other.mp.strictParts.map(_.entity.data)
      }

      override def toString: String = mp.toString
    }

    def strictEqualify[T](x: Either[T, Multipart.General.Strict]): Either[T, StrictEqualMultipart] =
      x.right.map(new StrictEqualMultipart(_))

    def parseTo(expected: Multipart.General.Strict*): Matcher[String] =
      multiParseTo(expected: _*).compose(_ :: Nil)

    def multiParseTo(expected: Multipart.General.Strict*): Matcher[Seq[String]] = multiParseTo(newParser, expected: _*)

    def multiParseTo(parser: HttpRequestParser, expected: Multipart.General.Strict*): Matcher[Seq[String]] =
      rawMultiParseTo(parser, expected: _*).compose(_ map prep)

    def rawMultiParseTo(expected: Multipart.General.Strict*): Matcher[Seq[String]] =
      rawMultiParseTo(newParser, expected: _*)

    def rawMultiParseTo(parser: HttpRequestParser, expected: Multipart.General.Strict*): Matcher[Seq[String]] =
      generalRawMultiParseTo(parser, expected.map(Right(_)): _*)

    def parseToError(status: StatusCode, info: ErrorInfo): Matcher[String] =
      generalMultiParseTo(Left(MessageStartError(status, info))).compose(_ :: Nil)

    def generalMultiParseTo(expected: Either[RequestOutput, Multipart.General.Strict]*): Matcher[Seq[String]] =
      generalRawMultiParseTo(expected: _*).compose(_ map prep)

    def generalRawMultiParseTo(expected: Either[RequestOutput, Multipart.General.Strict]*): Matcher[Seq[String]] =
      generalRawMultiParseTo(newParser, expected: _*)

    def generalRawMultiParseTo(
      parser:   HttpRequestParser,
      expected: Either[RequestOutput, Multipart.General.Strict]*): Matcher[Seq[String]] =
      equal(expected.map(strictEqualify))
        .matcher[Seq[Either[RequestOutput, StrictEqualMultipart]]] compose multiParse(parser)

    def multiParse(parser: HttpRequestParser)(input: Seq[String]): Seq[Either[RequestOutput, StrictEqualMultipart]] =
      Source(input.toList)
        .map(bytes ⇒ SessionBytes(TLSPlacebo.dummySession, ByteString(bytes)))
        .via(parser).named("parser")
        .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x.isInstanceOf[EntityStreamError])
        .prefixAndTail(1)
        .collect {
          case (Seq(RequestStart(method, uri, protocol, headers, createEntity, _, close)), entityParts) ⇒
            closeAfterResponseCompletion :+= close
            Right(HttpRequest(method, uri, headers, createEntity(entityParts), protocol))
          case (Seq(x @ (MessageStartError(_, _) | EntityStreamError(_))), rest) ⇒
            rest.runWith(Sink.cancelled)
            Left(x)
        }
        .concatSubstreams
        .flatMapConcat { requestEither ⇒
          Source.fromFuture {
            requestEither match {
              case Right(request) ⇒ request.entity.toStrict(defaultTimeout).flatMap(buildBodyParts)
              case Left(error)    ⇒ FastFuture.successful(Left(error))
            }
          }
        }
        .map(strictEqualify(_))
        .limit(100000).runWith(Sink.seq)
        .awaitResult(awaitAtMost)

    protected def parserSettings: ParserSettings = ParserSettings(system)

    protected def newParser = new HttpRequestParser(parserSettings, false, HttpHeaderParser(parserSettings, system.log))

    def prep(response: String): String = response.stripMarginWithNewline(mode.lineFeed)

    def contentLength(data: String): Int = prep(data).length

    def contentText(data: String): String = prep(data)

    protected def buildBodyParts(entity: HttpEntity): Future[Right[Nothing, Strict]] = {
      // This implementation is inspired from MultipartUnmarshallers but simplified for the need of the test
      import BodyPartParser._
      val parser = new BodyPartParser(ContentTypes.`text/plain(UTF-8)`, entity.contentType.mediaType.params("boundary"), DefaultNoLogging, parserSettings)
      FastFuture.successful {
        entity match {
          case HttpEntity.Strict(ContentType(mediaType: MediaType.Multipart, _), data) ⇒
            val builder = new VectorBuilder[Multipart.General.BodyPart.Strict]()
            val iter = new IteratorInterpreter[ByteString, BodyPartParser.Output](Iterator.single(data), List(parser)).iterator
            iter.foreach {
              case BodyPartStart(headers, createEntity) ⇒
                val entity = createEntity(Source.empty) match {
                  case x: HttpEntity.Strict ⇒ x
                  case x                    ⇒ throw new IllegalStateException("Unexpected entity type from strict BodyPartParser: " + x)
                }
                builder += Multipart.General.BodyPart.Strict(entity, headers)
              case ParseError(errorInfo) ⇒ ModelParsingException(errorInfo)
              case x                     ⇒ throw new IllegalStateException(s"Unexpected BodyPartParser result $x in strict case")
            }
            Multipart.General.Strict(mediaType, builder.result())
          case x ⇒ throw new IllegalStateException(s"Unexpected non HttpEntity.Strict $x")
        }
      }.map(x ⇒ Right(x))
    }
  }

}

class RequestParserBodyPartSpecCRLF extends RequestParserBodyPartSpec {
  override def mode = RequestParserBodyPartSpecMode("CRLF", "\r\n")
}

class RequestParserBodyPartSpecLF extends RequestParserBodyPartSpec {
  override def mode = RequestParserBodyPartSpecMode("LF", "\n")
}
