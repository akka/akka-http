/*
 * Copyright (C) 2024 Lightbend Inc. <https://akka.io>
 */

package akka.http.jwt.javadsl

import akka.annotation.{ ApiMayChange, DoNotInherit, InternalApi }
import akka.http.jwt.internal.{ JwtSettingsImpl, JwtSupport }

/**
 * Public API but not intended for subclassing
 */
@ApiMayChange @DoNotInherit
abstract class JwtSettings private[akka] { self: JwtSettingsImpl =>

  def realm: String

  def devMode: Boolean
}
