/**
 * Copyright 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import akka.http.scaladsl.model._

//https://tools.ietf.org/html/rfc7231#section-7.1.3
/** Defines different values admitted to define a [[`Retry-After`]] header */
sealed abstract class RetryAfterParameter
final case class RetryAfterDuration(delayInSeconds: Long) extends RetryAfterParameter {
  require(delayInSeconds >= 0, "Retry-after header must not contain a negative delay in seconds")
}
final case class RetryAfterDateTime(dateTime: DateTime) extends RetryAfterParameter

