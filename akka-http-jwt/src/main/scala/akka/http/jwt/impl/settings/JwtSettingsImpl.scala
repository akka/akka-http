package akka.http.jwt.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.SettingsCompanionImpl
import akka.http.jwt.scaladsl.JwtSettings
import akka.http.jwt.util.JwtSupport.{ JwtNoneAlgorithmSecret, JwtSecret }
import com.typesafe.config.Config

@InternalApi
private[akka] case class JwtSettingsImpl(secrets: List[JwtSecret]) extends JwtSettings {

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object JwtSettingsImpl extends SettingsCompanionImpl[JwtSettingsImpl]("akka.http.jwt") {

  override def fromSubConfig(root: Config, c: Config): JwtSettingsImpl = {

    // FIXME
    new JwtSettingsImpl(List(JwtSecret("dev", Some("dev-issuer"), JwtNoneAlgorithmSecret)))
  }
}
