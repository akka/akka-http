/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.io.File

import akka.http.impl.engine.http2.FrameEvent.{ DataFrame, ParsedHeadersFrame, SettingsAckFrame, SettingsFrame, WindowUpdateFrame }
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{ FileIO, Source, Tcp }
import akka.stream.testkit.TestPublisher
import akka.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

import scala.util.matching.Regex
import scala.util.parsing.combinator.RegexParsers

sealed trait TestStep
case class SendFrame(frame: FrameEvent) extends TestStep
case class ExpectFrame(frame: FrameEvent) extends TestStep

case class TestScript(steps: Seq[TestStep]) {
  def prettyPrint: String = TestScript.prettyPrint(this)
}

object TestScript {
  def parse(input: String): TestScript = Parser.parse(input)

  def prettyPrint(script: TestScript): String = script.steps.map(prettyPrintStep).mkString("\n\n")

  private def prettyPrintStep(step: TestStep): String = step match {
    case SendFrame(frame)   => "> " + prettyPrintFrame(frame)
    case ExpectFrame(frame) => "< " + prettyPrintFrame(frame)
  }
  private def prettyPrintFrame(frame: FrameEvent): String = frame match {
    case ParsedHeadersFrame(streamId, endStream, pairs, _) =>
      val es = if (endStream) " END_STREAM" else ""
      s"HEADERS $streamId$es" + pairs.sortBy(_._1).map { case (k, v) => s"  $k -> $v" }.mkString("\n", "\n", "")
    case DataFrame(streamId, endStream, payload) =>
      val es = if (endStream) " END_STREAM" else ""
      val data =
        if (payload.forall(b => b >= 0x20 && b < 0x80)) s""""${payload.utf8String}""""
        else payload.map(_.formatted("%02x")).mkString(" ")

      s"DATA $streamId$es (${payload.size} bytes) | $data"
    case WindowUpdateFrame(streamId, delta) =>
      s"WINDOW_UPDATE $streamId $delta"
    case SettingsFrame(settings) =>
      s"SETTINGS" + settings.map(s => s"${s.identifier.productPrefix} -> ${s.value}").mkString("\n", "\n", "")
    case SettingsAckFrame(_) =>
      s"SETTINGS ACK"
  }

  private object Parser extends RegexParsers {
    override type Elem = Char
    type Tokens = Any
    override val skipWhitespace = true
    override protected val whiteSpace: Regex = """(\s+(#[^\n]*)?)+""".r

    def script: Parser[TestScript] =
      rep(step).map(TestScript(_))

    def step: Parser[TestStep] = sendFrame | expectFrame

    def sendFrame: Parser[SendFrame] = ">" ~> frame ^^ SendFrame
    def expectFrame: Parser[ExpectFrame] = "<" ~> frame ^^ ExpectFrame

    def frame: Parser[FrameEvent] = headers | data | windowUpdate | settings

    def headers: Parser[FrameEvent] = "HEADERS" ~> intValue ~ endStream ~ rep(keyValue) ^^ { case id ~ end ~ kvs => ParsedHeadersFrame(id, end, kvs, None) }
    def settings: Parser[FrameEvent] = "SETTINGS" ~> (
      "ACK" ^^^ SettingsAckFrame(Nil) |
      rep(setting) ^^ SettingsFrame
    )
    def data: Parser[FrameEvent] = "DATA" ~> intValue ~ endStream ~ bytes ^^ { case id ~ end ~ data => DataFrame(id, end, data) }
    def windowUpdate: Parser[FrameEvent] = "WINDOW_UPDATE" ~> intValue ~ intValue ^^ { case id ~ delta => WindowUpdateFrame(id, delta) }

    def endStream: Parser[Boolean] = "END_STREAM".? ^^ (_.isDefined)
    def keyValue: Parser[(String, String)] = """[:\-\w]+""".r ~ "->" ~ """.+""".r ^^ { case key ~ _ ~ value => key -> value }

    def setting: Parser[FrameEvent.Setting] = settingIdentifier ~ "->" ~ intValue ^^ { case setting ~ _ ~ value => FrameEvent.Setting(setting, value) }
    def settingIdentifier: Parser[Http2Protocol.SettingIdentifier] =
      ("SETTINGS_MAX_CONCURRENT_STREAMS" ^^^ Http2Protocol.SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS) |
        ("SETTINGS_INITIAL_WINDOW_SIZE" ^^^ Http2Protocol.SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE)

    def lengthAnnotation: Parser[Int] = "(" ~> intValue <~ "bytes" ~ ")"

    def intValue: Parser[Int] = "[0-9]+".r ^^ (_.toInt)
    def bytes: Parser[ByteString] =
      lengthAnnotation.? ~ "|".? ~!
        (rep(
          hexBytes |
            (dquotedString ^^ (ByteString(_)))) ^^ (_.foldLeft(ByteString.empty)(_ ++ _))) ^? ({
            case length ~ _ ~ data if length.forall(_ == data.size) => data

          }, { case length ~ _ ~ data => s"Data size [${data.size}] did not equal annotated length [${length.get}]" })

    def hexBytes: Parser[ByteString] = rep1("[0-9a-fA-F]{2}".r) ^^ (bs => ByteString(bs.map(java.lang.Short.parseShort(_, 16).toByte)))
    def dquotedString: Parser[String] = "\"[^\"]*\"".r ^^ (_.drop(1).dropRight(1))

    def parse(input: String): TestScript =
      phrase(script)(new scala.util.parsing.input.CharArrayReader(input.toCharArray)) match {
        case Success(res, _) => res
        case x: NoSuccess    => throw new RuntimeException(s"Parsing failed: $x")
      }
  }
}

class TestScriptSpec extends AnyWordSpec with Matchers {
  "TestScriptParser" should {
    "parse HEADERS steps with key/value pairs" in {
      TestScript.parse(
        """> HEADERS 1 END_STREAM
          |  :method    -> GET
          |  :scheme    -> http
          |  :path      -> /info
          |  :authority -> www.example.com
          |
          |< HEADERS 1 END_STREAM
          |  :status -> 404""".stripMargin).steps shouldEqual
        Seq(
          SendFrame(
            ParsedHeadersFrame(1, true, Seq(
              ":method" -> "GET",
              ":scheme" -> "http",
              ":path" -> "/info",
              ":authority" -> "www.example.com"
            ), None)),

          ExpectFrame(
            ParsedHeadersFrame(1, true, Seq(
              ":status" -> "404",
            ), None)
          )
        )
    }
    "parse SETTINGS" in {
      TestScript.parse(
        """> SETTINGS
          |  SETTINGS_MAX_CONCURRENT_STREAMS -> 100
          |""".stripMargin
      ).steps shouldEqual Seq(
          SendFrame(SettingsFrame(Seq(FrameEvent.Setting(Http2Protocol.SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 100))))
        )
    }
    "parse DATA" in {
      TestScript.parse(
        """> DATA 3 "Hello World!"
          |
          |< DATA 3 | 08 07 ac 18 " test " DE 12 # with whitespace inside of quotes
          |< DATA 3 END_STREAM (4 bytes) | 12 15 28 30 # with optional length annotation
          |""".stripMargin
      ).steps shouldEqual Seq(
          SendFrame(DataFrame(3, false, ByteString("Hello World!"))),
          ExpectFrame(DataFrame(3, false, ByteString(8, 7, 0xac, 0x18) ++ ByteString(" test ") ++ ByteString(0xde, 0x12))),
          ExpectFrame(DataFrame(3, true, ByteString(0x12, 0x15, 0x28, 0x30)))
        )
    }

    "ignore line comments" in {
      TestScript.parse(
        """> SETTINGS
          |  SETTINGS_MAX_CONCURRENT_STREAMS -> 100 # 100 should be enough
          |
          |# trust me it should be enough
          |# last line""".stripMargin
      ).steps shouldEqual Seq(
          SendFrame(SettingsFrame(Seq(FrameEvent.Setting(Http2Protocol.SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 100))))
        )
    }

    "pretty-print HEADERS" in {
      val parsed =
        TestScript.parse(
          """> HEADERS 1 END_STREAM
          |  :method    -> GET
          |  :scheme    -> http
          |  :path      -> /info
          |  :authority -> www.example.com
          |
          |> DATA 3 "Hello World!"
          |
          |< HEADERS 1 END_STREAM
          |  :status -> 404""".stripMargin)
      println(parsed.prettyPrint)
    }
  }
}

class Http2FrameExchangeTests extends AkkaSpecWithMaterializer(
  """
    |akka.http.server.preview.enable-http2 = on
    |akka.http.server.http2.log-frames = on
    |""".stripMargin
) {
  import system.dispatcher

  "Server frame exchange tests" should {
    findServerTests().foreach(runTest)
  }

  def runTest(file: File): Unit =
    file.getName.dropRight(5) in {
      val script = loadScriptResource(file)
      println(script.prettyPrint)

      val binding = Http().newServerAt("127.0.0.1", 0).bind(serverHandler).futureValue

      val out = TestPublisher.probe[ByteString]()
      val in = Http2FrameProbe()

      Source.fromPublisher(out)
        .via(Tcp().outgoingConnection("127.0.0.1", binding.localAddress.getPort))
        .runWith(in.sink)

      out.sendNext(Http2Protocol.ClientConnectionPreface)

      val sending = new Http2FrameSending with Http2FrameHpackSupport {
        override def frameProbeDelegate: Http2FrameProbe = in
        override def sendBytes(bytes: ByteString): Unit = out.sendNext(bytes)
      }

      def runRemaining(steps: Seq[TestStep], collected: Seq[TestStep]): TestScript = steps match {
        case Nil => TestScript(collected)
        case (f @ SendFrame(frame)) +: rest =>
          sending.sendFrame(frame)
          runRemaining(rest, collected :+ f)
        case ExpectFrame(frame) +: rest =>
          val f = in.expectFrameOrParsedHeaders() match {
            case f: ParsedHeadersFrame =>
              f.copy(keyValuePairs = f.keyValuePairs.filterNot { case (k, v) => k == "date" || k == "server" })
            case x => x
          }
          runRemaining(rest, collected :+ ExpectFrame(f))
      }

      try {
        val realScript = runRemaining(script.steps, Vector.empty)
        realScript.prettyPrint shouldEqual script.prettyPrint
      } finally {
        out.sendComplete()
        in.plainDataProbe.cancel()
      }
    }

  def serverHandler: Route = {
    import akka.http.scaladsl.server.Directives._
    concat(
      path("hello-world")(complete("Hello World!")),
      path("request-toString") {
        extractRequest { req =>
          complete(req.toString)
        }
      },
      path("count-request-bytes") {
        extractStrictEntity(100.millis) { entity =>
          complete(s"Entity size was: ${entity.data.size} bytes")
        }
      },
      path("echo-request-stream") {
        extractRequestEntity { entity =>
          complete(entity)
        }
      }
    )
  }

  def loadScriptResource(file: File): TestScript = {
    def slurp(): String =
      FileIO.fromPath(file.toPath)
        .runFold(ByteString.empty)(_ ++ _)
        .map(_.utf8String)
        .futureValue

    TestScript.parse(slurp())
  }

  def findServerTests(): Seq[File] = {
    val directory = new File(getClass.getClassLoader.getResource("tests/server").toURI)
    directory.listFiles().filter(_.getName.endsWith("test")).toVector
  }
}
