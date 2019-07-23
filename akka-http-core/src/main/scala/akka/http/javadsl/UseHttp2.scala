/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import akka.annotation.DoNotInherit
import akka.http.scaladsl
import akka.http.scaladsl.UseHttp2._

/** This class is planned to disappear in 10.2.0 */
@Deprecated
@DoNotInherit
abstract class UseHttp2 {
  /** This method is planned to disappear in 10.2.0 */
  @Deprecated
  def asScala: scaladsl.UseHttp2
}

/** This class is planned to disappear in 10.2.0 */
@Deprecated
object UseHttp2 {
  /** This method is planned to disappear in 10.2.0 */
  @Deprecated
  def always: UseHttp2 = Always
  /** This method is planned to disappear in 10.2.0 */
  @Deprecated
  def negotiated: UseHttp2 = Negotiated
  /** This method is planned to disappear in 10.2.0 */
  @Deprecated
  def never: UseHttp2 = Never
}
