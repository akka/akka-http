/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http
package scaladsl
package unmarshalling
package sse

import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString
import org.scalatest.{ AsyncWordSpec, Matchers }

final class LineParserSpec extends AsyncWordSpec with Matchers with BaseUnmarshallingSpec {

  "A LineParser" should {

    "parse lines terminated with either CR, LF or CRLF" in {
      Source
        .single(ByteString("line1\nline2\rline3\r\nline4\nline5\rline6\r\n\n"))
        .via(new LineParser(1048576))
        .runWith(Sink.seq)
        .map(_ shouldBe Vector("line1", "line2", "line3", "line4", "line5", "line6", ""))
    }

    "ignore a trailing non-terminated line" in {
      Source
        .single(ByteString("line1\nline2\rline3\r\nline4\nline5\rline6\r\n\nincomplete"))
        .via(new LineParser(1048576))
        .runWith(Sink.seq)
        .map(_ shouldBe Vector("line1", "line2", "line3", "line4", "line5", "line6", ""))
    }

    "ignore a trailing non-terminated line when parsing byte by byte" in {
      Source(ByteString("line1\nline2\rline3\r\nline4\nline5\rline6\r\n\nincomplete").map(ByteString(_)))
        .via(new LineParser(1048576))
        .runWith(Sink.seq)
        .map(_ shouldBe Vector("line1", "line2", "line3", "line4", "line5", "line6", ""))
    }

    "parse a line splitted into many chunks" in {
      Source(('a'.to('z') :+ '\n').map(ByteString(_)))
        .via(new LineParser(1048576))
        .runWith(Sink.seq)
        .map(_ shouldBe Vector('a'.to('z').mkString))
    }

    "parse lines with terminations between chunks" in {
      Source(("after" :: "\n" :: "\n" :: "before" :: "\r" :: "middle" :: "\n" :: Nil).map(ByteString(_)))
        .via(new LineParser(1048576))
        .runWith(Sink.seq)
        .map(_ shouldBe Vector("after", "", "before", "middle"))
    }
  }
}
