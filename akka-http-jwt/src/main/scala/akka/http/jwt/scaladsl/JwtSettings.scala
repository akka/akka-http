package akka.http.jwt.scaladsl

import akka.actor.ClassicActorSystemProvider
import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.jwt.impl.settings.JwtSettingsImpl
import akka.http.jwt.util.JwtSupport.JwtSecret
import com.typesafe.config.Config

@ApiMayChange @DoNotInherit
trait JwtSettings {
  def secrets: List[JwtSecret]
}

object JwtSettings {
  def apply(system: ClassicActorSystemProvider): JwtSettings =
    JwtSettingsImpl(system.classicSystem) // FIXME
  def apply(config: Config): JwtSettings =
    JwtSettingsImpl(config) // FIXME
}
