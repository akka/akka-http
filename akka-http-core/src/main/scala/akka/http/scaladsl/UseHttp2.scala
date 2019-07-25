/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

/**
 * Specify whether to support HTTP/2: never, negotiated, or always.
 */
@deprecated("This trait is planned to disappear in 10.2.0", "10.1.9")
sealed trait UseHttp2 extends akka.http.javadsl.UseHttp2 {
  @deprecated("This method is planned to disappear in 10.2.0", "10.1.9")
  override def asScala: UseHttp2 = this
}
@deprecated("This object is planned to disappear in 10.2.0", "10.1.9")
object UseHttp2 {
  @deprecated("This trait is planned to disappear in 10.2.0", "10.1.9")
  object Never extends UseHttp2
  @deprecated("This trait is planned to disappear in 10.2.0", "10.1.9")
  object Negotiated extends UseHttp2
  @deprecated("This trait is planned to disappear in 10.2.0", "10.1.9")
  object Always extends UseHttp2
}
