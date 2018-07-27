/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

sealed abstract class StrictTransportSecurityDirective
final case class IgnoredDirective(value: String) extends StrictTransportSecurityDirective
case object IncludeSubDomains extends StrictTransportSecurityDirective
final case class MaxAge(value: Long) extends StrictTransportSecurityDirective
