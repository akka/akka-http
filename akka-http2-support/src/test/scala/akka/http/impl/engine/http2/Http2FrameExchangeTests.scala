package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.FrameEvent.{ DataFrame, ParsedHeadersFrame, SettingsFrame }
import akka.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

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

  def prettyPrint(script: TestScript): String = ???

  private object Parser extends RegexParsers {
    override type Elem = Char
    type Tokens = Any
    override val skipWhitespace = true
    override protected val whiteSpace: Regex = """(\s+(#.*\n)?)+""".r

    def script: Parser[TestScript] =
      rep(step).map(TestScript(_))

    def step: Parser[TestStep] = sendFrame | expectFrame

    def sendFrame: Parser[SendFrame] = ">" ~> frame ^^ SendFrame
    def expectFrame: Parser[ExpectFrame] = "<" ~> frame ^^ ExpectFrame

    def frame: Parser[FrameEvent] = headers | settings | data

    def headers: Parser[FrameEvent] = "HEADERS" ~> intValue ~ endStream ~ rep(keyValue) ^^ { case id ~ end ~ kvs => ParsedHeadersFrame(id, end, kvs, None) }
    def settings: Parser[FrameEvent] = "SETTINGS" ~> rep(setting) ^^ SettingsFrame
    def data: Parser[FrameEvent] = "DATA" ~> intValue ~ endStream ~ bytes ^^ { case id ~ end ~ data => DataFrame(id, end, data) }

    def endStream: Parser[Boolean] = "END_STREAM".? ^^ (_.isDefined)
    def keyValue: Parser[(String, String)] = """[:\w]+""".r ~ "->" ~ """.+""".r ^^ { case key ~ _ ~ value => key -> value }

    def setting: Parser[FrameEvent.Setting] = settingIdentifier ~ "->" ~ intValue ^^ { case setting ~ _ ~ value => FrameEvent.Setting(setting, value) }
    def settingIdentifier: Parser[Http2Protocol.SettingIdentifier] =
      "SETTINGS_MAX_CONCURRENT_STREAMS" ^^^ Http2Protocol.SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS

    def intValue: Parser[Int] = "[0-9]+".r ^^ (_.toInt)
    def bytes: Parser[ByteString] =
      rep(
        hexBytes |
          (dquotedString ^^ (ByteString(_)))) ^^ (_.reduce(_ ++ _))

    def hexBytes: Parser[ByteString] = rep1("[0-9a-fA-F]{2}".r) ^^ (bs => ByteString(bs.map(java.lang.Short.parseShort(_, 16).toByte)))
    def dquotedString: Parser[String] = "\"" ~> "[^\"]*".r <~ "\""

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
          |< DATA 3 08 07 ac 18 "test" DE 12
          |< DATA 3 END_STREAM 12 15 28 30
          |""".stripMargin
      ).steps shouldEqual Seq(
          SendFrame(DataFrame(3, false, ByteString("Hello World!"))),
          ExpectFrame(DataFrame(3, false, ByteString(8, 7, 0xac, 0x18) ++ ByteString("test") ++ ByteString(0xde, 0x12))),
          ExpectFrame(DataFrame(3, true, ByteString(0x12, 0x15, 0x28, 0x30)))
        )
    }

    "ignore line comments" in {
      TestScript.parse(
        """> SETTINGS
          |  SETTINGS_MAX_CONCURRENT_STREAMS -> 100 # 100 should be enough
          |
          |# trust me it should be enough
          |""".stripMargin
      ).steps shouldEqual Seq(
          SendFrame(SettingsFrame(Seq(FrameEvent.Setting(Http2Protocol.SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 100))))
        )
    }

    "pretty-print HEADERS" in {

    }
  }
}

class Http2FrameExchangeTests {

}
