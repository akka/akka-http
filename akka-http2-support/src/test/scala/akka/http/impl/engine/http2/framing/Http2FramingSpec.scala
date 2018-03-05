/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2
package framing

import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.ws.{ BitBuilder, WithMaterializerSpec }
import akka.http.impl.util._
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString
import akka.testkit._
import org.scalatest.matchers.Matcher
import org.scalatest.{ FreeSpec, Matchers }

import scala.collection.immutable
import scala.concurrent.duration._

class Http2FramingSpec extends FreeSpec with Matchers with WithMaterializerSpec {
  import BitBuilder._
  import akka.http.impl.engine.http2._

  "The HTTP/2 parser/renderer round-trip should work for" - {
    "DATA frames" - {
      "without padding" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=5   # length
            00000000     # type = 0x0 = DATA
            00000001     # flags = END_STREAM
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=234223ab # stream ID
            xxxxxxxx=61  # data
            xxxxxxxx=62
            xxxxxxxx=63
            xxxxxxxx=64
            xxxxxxxx=65
         """ should parseTo(DataFrame(0x234223ab, endStream = true, ByteString("abcde")))
      }
      "with padding" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=c   # length = 11 = 1 byte padding size + 5 bytes padding + 6 bytes data
            00000000     # type = 0x0 = DATA
            00001000     # flags = PADDED
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=234223ab # stream ID
            xxxxxxxx=5
            xxxxxxxx=62  # data
            xxxxxxxx=63
            xxxxxxxx=64
            xxxxxxxx=65
            xxxxxxxx=66
            xxxxxxxx=67
            00000000     # padding
            00000000
            00000000
            00000000
            00000000
         """ should parseTo(DataFrame(0x234223ab, endStream = false, ByteString("bcdefg")), checkRendering = false)
      }
    }
    "HEADER frames" - {
      "without padding + priority settings" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=5   # length
            xxxxxxxx=1   # type = 0x1 = HEADERS
            00000100     # flags = END_HEADERS
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=3546 # stream ID
            xxxxxxxx=61  # data
            xxxxxxxx=62
            xxxxxxxx=63
            xxxxxxxx=64
            xxxxxxxx=65
         """ should parseTo(HeadersFrame(0x3546, endStream = false, endHeaders = true, ByteString("abcde"), None))
      }
      "with padding but without priority settings" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=a   # length = 10 = 1 byte padding size + 3 bytes padding + 6 bytes payload
            xxxxxxxx=1   # type = 0x1 = HEADERS
            00001000     # flags = PADDED
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=3546 # stream ID
            xxxxxxxx=3   # amount of padding
            xxxxxxxx=62  # data
            xxxxxxxx=63
            xxxxxxxx=64
            xxxxxxxx=65
            xxxxxxxx=66
            xxxxxxxx=67
            00000000     # padding
            00000000
            00000000
         """ should parseTo(HeadersFrame(0x3546, endStream = false, endHeaders = false, ByteString("bcdefg"), None), checkRendering = false)
      }
      "without padding but with priority settings" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=9   # length = 9 = 4 bytes stream dependency + 1 byte weight + 4 bytes payload
            xxxxxxxx=1   # type = 0x1 = HEADERS
            00100000     # flags = PRIORITY
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=3546 # stream ID
            0            # E flag unset
             xxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=abdef0 # stream dependency
            xxxxxxxx=bd  # weight
            xxxxxxxx=63  # data
            xxxxxxxx=64
            xxxxxxxx=65
            xxxxxxxx=66
         """ should parseTo(HeadersFrame(0x3546, endStream = false, endHeaders = false, ByteString("cdef"), Some(PriorityFrame(0x3546, false, 0xabdef0, 0xbd))))
      }
      "PUSH_PROMISE frame" - {
        "without padding" in {
          b"""xxxxxxxx
              xxxxxxxx
              xxxxxxxx=9   # length
              xxxxxxxx=5   # type = 0x5 = PUSH_PROMISE
              00000100     # flags = END_HEADERS
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=12345 # stream ID
              0xxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=12 # promised stream ID
              xxxxxxxx=61 # payload
              xxxxxxxx=62
              xxxxxxxx=63
              xxxxxxxx=64
              xxxxxxxx=65
         """ should parseTo(PushPromiseFrame(0x12345, endHeaders = true, 0x12, ByteString("abcde")))
        }
        "with padding" in {
          b"""xxxxxxxx
              xxxxxxxx
              xxxxxxxx=c  # length
              xxxxxxxx=5  # type = 0x5 = PUSH_PROMISE
              00001100    # flags = END_HEADERS | PADDED
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=54321 # stream ID
              xxxxxxxx=2
              0xxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=4242 # promised stream ID
              xxxxxxxx=61 # payload
              xxxxxxxx=62
              xxxxxxxx=63
              xxxxxxxx=64
              xxxxxxxx=65
              00000000    # padding
              00000000
         """ should parseTo(PushPromiseFrame(0x54321, endHeaders = true, 0x4242, ByteString("abcde")), checkRendering = false)
        }
      }
      "with padding and priority settings" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=f   # length = 15 = 1 byte padding length + 4 bytes stream dependency + 1 byte weight + 4 bytes payload + 5 bytes padding
            xxxxxxxx=1   # type = 0x1 = HEADERS
            00101000     # flags = PRIORITY | PADDED
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=348 # stream ID
            xxxxxxxx=5   # amount of padding
            0            # E flag unset
             xxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=abd # stream dependency
            xxxxxxxx=ef  # weight
            xxxxxxxx=63  # data
            xxxxxxxx=64
            xxxxxxxx=65
            xxxxxxxx=66
            00000000     # padding
            00000000
            00000000
            00000000
            00000000
         """ should parseTo(HeadersFrame(0x348, endStream = false, endHeaders = false, ByteString("cdef"), Some(PriorityFrame(0x348, false, 0xabd, 0xef))), checkRendering = false)
      }
    }
    "SETTINGS frame" - {
      "empty" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=0   # length
            00000100     # type = 0x4 = SETTINGS
            00000000     # no flags
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=0   # no stream ID
         """ should parseTo(SettingsFrame(Nil))
      }
      "with one setting" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=6   # length
            00000100     # type = 0x4 = SETTINGS
            00000000     # no flags
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=0   # no stream ID
            xxxxxxxx
            xxxxxxxx=4
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=20000
         """ should parseTo(SettingsFrame(List(Http2Protocol.SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE → 0x20000)))
      }
      "with two settings" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=c   # length
            00000100     # type = 0x4 = SETTINGS
            00000000     # no flags
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=0   # no stream ID
            xxxxxxxx
            xxxxxxxx=5
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=424242
            xxxxxxxx
            xxxxxxxx=3
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=123
         """ should parseTo(SettingsFrame(List(
          Http2Protocol.SettingIdentifier.SETTINGS_MAX_FRAME_SIZE → 0x424242,
          Http2Protocol.SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS → 0x123
        )))
      }
      "ack" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=0   # length
            00000100     # type = 0x4 = SETTINGS
            00000001     # ACK
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=0   # no stream ID
         """ should parseTo(SettingsAckFrame(Nil))
      }
    }
    "PING frame" - {
      "without ack" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=8   # length
            00000110     # type = 0x6 = PING
            00000000     # no flags
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=0   # no stream ID
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=1234567890abcdef
         """ should parseTo(PingFrame(ack = false, hex"1234567890abcdef"))
      }
      "with ack" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=8   # length
            00000110     # type = 0x6 = PING
            00000001     # ACK
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=0   # no stream ID
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=fedcba09
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=87654321
         """ should parseTo(PingFrame(ack = true, hex"fedcba0987654321"))
      }
    }
    "RST_FRAME" in {
      b"""xxxxxxxx
          xxxxxxxx
          xxxxxxxx=4   # length
          00000011     # type = 0x3 = RST_STREAM
          00000000     # no flags
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx=23  # stream ID = 23
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx=2   # error code = 0x2 = INTERNAL_ERROR
         """ should parseTo(RstStreamFrame(0x23, ErrorCode.INTERNAL_ERROR))
    }
    "PRIORITY_FRAME" in {
      b"""xxxxxxxx
          xxxxxxxx
          xxxxxxxx=5   # length
          00000010     # type = 0x2 = PRIORITY
          00000000     # no flags
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx=23  # stream ID = 23
          1            # E flag set
           xxxxxxx
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx=100 # stream dependency
          xxxxxxxx=fa  # weight
         """ should parseTo(PriorityFrame(0x23, exclusiveFlag = true, streamDependency = 0x100, weight = 0xfa))
    }
    "PRIORITY_FRAME2" in {
      b"""xxxxxxxx
          xxxxxxxx
          xxxxxxxx=5   # length
          00000010     # type = 0x2 = PRIORITY
          00000000     # no flags
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx=dead  # stream ID = dead
          0              # E flag not set
           xxxxxxx
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx=beef # stream dependency
          xxxxxxxx=15   # weight
         """ should parseTo(PriorityFrame(0xdead, exclusiveFlag = false, streamDependency = 0xbeef, weight = 0x15))
    }
    "WINDOW_UPDATE" in {
      b"""xxxxxxxx
          xxxxxxxx
          xxxxxxxx=4   # length
          00001000     # type = 0x8 = WINDOW_UPDATE          xxxxxxxx=42  # stream ID = 42
          00000000     # no flags
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx=42  # stream ID = 42
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx
          xxxxxxxx=12345 # windowSizeIncrement
         """ should parseTo(WindowUpdateFrame(0x42, 0x12345))
    }
    "GOAWAY frame" - {
      "without debug data" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=8   # length
            00000111     # type = 0x7 = GOAWAY
            00000000     # no flags
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=0   # stream ID = 0
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=21  # last stream ID = 21
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=2   # error code = 0x2 = INTERNAL_ERROR
         """ should parseTo(GoAwayFrame(0x21, ErrorCode.INTERNAL_ERROR))
      }
      "with debug data" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=9  # length
            00000111     # type = 0x7 = GOAWAY
            00000000     # no flags
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=0   # stream ID = 0
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=21  # last stream ID = 21
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=1   # error code = 0x1 = PROTOCOL_ERROR
            xxxxxxxx=1
         """ should parseTo(GoAwayFrame(0x21, ErrorCode.PROTOCOL_ERROR, hex"1"))
      }
    }
  }

  private def parseTo(events: FrameEvent*): Matcher[ByteString] =
    parseMultipleTo(events: _*).compose(Seq(_)) // TODO: try random chunkings

  private def parseTo(event: FrameEvent, checkRendering: Boolean): Matcher[ByteString] =
    parseMultipleTo(Seq(event), checkRendering).compose(Seq(_)) // TODO: try random chunkings

  private def parseMultipleTo(events: FrameEvent*): Matcher[Seq[ByteString]] =
    parseMultipleTo(events, checkRendering = true)

  private def parseMultipleTo(events: Seq[FrameEvent], checkRendering: Boolean): Matcher[Seq[ByteString]] =
    equal(events).matcher[Seq[FrameEvent]].compose {
      (chunks: Seq[ByteString]) ⇒
        val result = parseToEvents(chunks)
        result shouldEqual events

        if (checkRendering) {
          val rendered = renderToByteString(result)
          rendered shouldEqual chunks.reduce(_ ++ _)
        }
        result
    }

  private def parseToEvents(bytes: Seq[ByteString]): immutable.Seq[FrameEvent] =
    Source(bytes.toVector).via(new Http2FrameParsing(shouldReadPreface = false)).runWith(Sink.seq)
      .awaitResult(1.second.dilated)
  private def renderToByteString(events: immutable.Seq[FrameEvent]): ByteString =
    Source(events).map(FrameRenderer.render).runFold(ByteString.empty)(_ ++ _)
      .awaitResult(1.second.dilated)
}
