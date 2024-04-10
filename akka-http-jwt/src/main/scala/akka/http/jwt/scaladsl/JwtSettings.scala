/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.scaladsl

import akka.actor.ClassicActorSystemProvider
import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.jwt.internal.{JwtSettingsImpl, JwtSupport}
import com.typesafe.config.Config

@ApiMayChange @DoNotInherit
trait JwtSettings {
  def jwtSupport: JwtSupport
  def realm: String
}

object JwtSettings {
  def apply(system: ClassicActorSystemProvider): JwtSettings =
    JwtSettingsImpl(system.classicSystem)
  def apply(config: Config): JwtSettings =
    JwtSettingsImpl(config)
}
