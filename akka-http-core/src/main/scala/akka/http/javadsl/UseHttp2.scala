/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import akka.annotation.DoNotInherit
import akka.http.scaladsl
import akka.http.scaladsl.UseHttp2._

@DoNotInherit
abstract class UseHttp2 {
  def asScala: scaladsl.UseHttp2
}

object UseHttp2 {
  def always: UseHttp2 = Always
  def negotiated: UseHttp2 = Negotiated
  def never: UseHttp2 = Never
}
