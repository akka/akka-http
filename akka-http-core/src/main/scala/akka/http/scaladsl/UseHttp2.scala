/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

/**
 * Specify whether to support HTTP/2: never, negotiated, or always.
 */
sealed trait UseHttp2 extends akka.http.javadsl.UseHttp2 {
  override def asScala: UseHttp2 = this
}
object UseHttp2 {
  object Never extends UseHttp2
  object Negotiated extends UseHttp2
  object Always extends UseHttp2
}
