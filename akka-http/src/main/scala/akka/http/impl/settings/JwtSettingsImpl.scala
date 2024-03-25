package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.SettingsCompanionImpl
import akka.http.scaladsl.server.util.JwtSupport.{ JwtNoneAlgorithmSecret, JwtSecret }
import com.typesafe.config.Config

import scala.jdk.CollectionConverters.CollectionHasAsScala

@InternalApi
private[akka] case class JwtSettingsImpl(secrets: List[JwtSecret]) extends akka.http.scaladsl.settings.JwtSettings {

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
