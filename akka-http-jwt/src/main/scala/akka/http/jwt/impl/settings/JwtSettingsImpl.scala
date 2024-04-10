package akka.http.jwt.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.SettingsCompanionImpl
import akka.http.jwt.scaladsl.JwtSettings
import akka.http.jwt.util.JwtSupport
import akka.http.jwt.util.JwtSupport.{ JwtNoneAlgorithmSecret, JwtSecret }
import com.typesafe.config.Config

@InternalApi
private[akka] case class JwtSettingsImpl(jwtSupport: JwtSupport, realm: String) extends JwtSettings {

  override def productPrefix = "JwtSettings"
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object JwtSettingsImpl extends SettingsCompanionImpl[JwtSettingsImpl]("akka.http.jwt") {

  override def fromSubConfig(root: Config, inner: Config): JwtSettingsImpl = {
    val c = inner.withFallback(root.getConfig(prefix))
    new JwtSettingsImpl(
      JwtSupport.fromConfig(c),
      c.getString("realm"))
  }
}
