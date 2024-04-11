/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.javadsl

import akka.annotation.{ ApiMayChange, DoNotInherit, InternalApi }
import akka.http.jwt.internal.{ JwtSettingsImpl, JwtSupport }

/** Not for user extension */
@ApiMayChange @DoNotInherit
abstract class JwtSettings private[akka] { self: JwtSettingsImpl =>
  /** INTERNAL API */
  @InternalApi private[akka] def jwtSupport: JwtSupport

  def realm: String
}
