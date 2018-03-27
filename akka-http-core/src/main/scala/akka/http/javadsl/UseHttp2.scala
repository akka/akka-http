package akka.http.javadsl

import akka.annotation.DoNotInherit
import akka.http.scaladsl

@DoNotInherit
abstract class UseHttp2 {
  def asScala: scaladsl.UseHttp2
}

object UseHttp2 {
  def always: UseHttp2 = scaladsl.Always
  def negotiated: UseHttp2 = scaladsl.Negotiated
  def never: UseHttp2 = scaladsl.Never
}
