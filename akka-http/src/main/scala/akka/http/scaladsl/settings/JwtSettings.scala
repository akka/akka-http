package akka.http.scaladsl.settings

import akka.actor.ClassicActorSystemProvider
import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.impl.settings.{ CorsSettingsImpl, JwtSettingsImpl }
import akka.http.scaladsl.server.util.JwtSupport.JwtSecret
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
