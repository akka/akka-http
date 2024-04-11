/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.scaladsl

import akka.actor.ClassicActorSystemProvider
import akka.annotation.{ApiMayChange, DoNotInherit, InternalApi}
import akka.http.jwt.internal.{JwtSettingsImpl, JwtSupport}
import com.typesafe.config.Config

@ApiMayChange @DoNotInherit
trait JwtSettings extends akka.http.jwt.javadsl.JwtSettings { self: JwtSettingsImpl =>
  /** INTERNAL API */
  @InternalApi private[akka] override def jwtSupport: JwtSupport

  override def realm: String
}

object JwtSettings {
  def apply(system: ClassicActorSystemProvider): JwtSettings =
    JwtSettingsImpl(system.classicSystem)
  def apply(config: Config): JwtSettings =
    JwtSettingsImpl(config)
}
