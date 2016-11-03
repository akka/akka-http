package akka.http.impl.util

import akka.NotUsed
import akka.stream.TLSProtocol._
import akka.stream.scaladsl
import akka.stream.scaladsl.{ BidiFlow, Flow }
import akka.util.ByteString

object LogByteStringBidiStage {
  def logTLSBidi(name: String): scaladsl.BidiFlow[SslTlsOutbound, SslTlsOutbound, SslTlsInbound, SslTlsInbound, NotUsed] =
    BidiFlow.fromFlows(
      Flow[SslTlsOutbound].log(s"$name DOWN", {
        case SendBytes(bytes)       ⇒ "SendBytes " + printByteString(bytes)
        case n: NegotiateNewSession ⇒ n.toString
      }),
      Flow[SslTlsInbound].log(s"$name UP", {
        case s @ SessionTruncated         ⇒ println(s)
        case SessionBytes(session, bytes) ⇒ "SessionBytes " + printByteString(bytes)
      })
    )

  def logByteStringBidi(name: String): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
    BidiFlow.fromFlows(
      logByteString(s"$name DOWN"),
      logByteString(s"$name UP")
    )

  def logByteString(name: String): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].log(name, printByteString(_))

  def printByteString(bytes: ByteString, maxBytes: Int = 16 * 5): String = {
    val indent = " "

    def formatBytes(bs: ByteString): Iterator[String] = {
      def asHex(b: Byte): String = b formatted "%02X"
      def asASCII(b: Byte): Char =
        if (b >= 0x20 && b < 0x7f) b.toChar
        else '.'

      def formatLine(bs: ByteString): String = {
        val data = bs.toSeq
        val hex = data.map(asHex).mkString(" ")
        val ascii = data.map(asASCII).mkString
        f"$indent%s  $hex%-48s | $ascii"
      }
      def formatBytes(bs: ByteString): String =
        bs.grouped(16).map(formatLine).mkString("\n")

      val prefix = s"${indent}ByteString(${bs.size} bytes)"

      if (bs.size <= maxBytes * 2) Iterator(prefix + "\n", formatBytes(bs))
      else
        Iterator(
          s"$prefix first + last $maxBytes:\n",
          formatBytes(bs.take(maxBytes)),
          s"\n$indent                    ... [${bs.size - (maxBytes * 2)} bytes omitted] ...\n",
          formatBytes(bs.takeRight(maxBytes)))
    }

    formatBytes(bytes).mkString("")
  }
}
