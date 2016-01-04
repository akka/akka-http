/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import java.nio.charset.Charset

import akka.stream.scaladsl.{ Keep, Flow, BidiFlow }
import akka.stream.stage.{ TerminationDirective, SyncDirective, Context, PushPullStage }
import akka.util.ByteString

import pprint._
import pprint.Config.Defaults._
import pprint.Colors

object PPrintDebug {
  implicit def bytestringPrinter: PPrinter[ByteString] =
    PPrinter[ByteString] { (bs: ByteString, c: Config) ⇒
      val maxBytes = 16 * 5
      val indent = " " * (c.indent + 1)

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

      formatBytes(bs)
    }

  def layer[L: PPrint, R: PPrint](tag: String): BidiFlow[L, L, R, R, Unit] =
    BidiFlow.wrap(Flow[L].via(flow(tag + "-up")), Flow[R].via(flow(tag + "-down")))(Keep.none)

  val Enabled = false

  private[http] def flow[T: PPrint](marker: String): Flow[T, T, Unit] =
    if (Enabled)
      Flow[T].transform(() ⇒ new PushPullStage[T, T] {
        def println(str: String): Unit = scala.Console.println(str)

        override def onPush(element: T, ctx: Context[T]): SyncDirective = {
          println(s"$marker: PUSH\n${formatElement(element)}")
          ctx.push(element)
        }
        override def onPull(ctx: Context[T]): SyncDirective = {
          println(s"$marker: PULL")
          ctx.pull()
        }
        override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
          println(s"$marker: Error $cause")
          super.onUpstreamFailure(cause, ctx)
        }
        override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
          println(s"$marker: Complete")
          super.onUpstreamFinish(ctx)
        }
        override def onDownstreamFinish(ctx: Context[T]): TerminationDirective = {
          println(s"$marker: Cancel")
          super.onDownstreamFinish(ctx)
        }

        def formatElement(element: T): String =
          pprint.tokenize(element, width = 80, height = 14, colors = Colors.Colored).mkString
      })
    else Flow[T]
}
