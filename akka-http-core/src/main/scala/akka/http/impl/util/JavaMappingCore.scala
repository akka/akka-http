/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import akka.annotation.InternalApi
import akka.http.javadsl.{ ConnectionContext, HttpConnectionContext, HttpsConnectionContext, settings ⇒ js }
import akka.http.{ javadsl ⇒ jdsl, scaladsl ⇒ sdsl }

/** INTERNAL API */
@InternalApi
private[http] object JavaMappingCore extends JavaMappings {

  implicit object ConnectionContext extends Inherited[ConnectionContext, akka.http.scaladsl.ConnectionContext]
  implicit object HttpConnectionContext extends Inherited[HttpConnectionContext, akka.http.scaladsl.HttpConnectionContext]
  implicit object HttpsConnectionContext extends Inherited[HttpsConnectionContext, akka.http.scaladsl.HttpsConnectionContext]

  implicit object ClientConnectionSettings extends Inherited[js.ClientConnectionSettings, akka.http.scaladsl.settings.ClientConnectionSettings]
  implicit object ConnectionPoolSettings extends Inherited[js.ConnectionPoolSettings, akka.http.scaladsl.settings.ConnectionPoolSettings]
  implicit object ServerSettings extends Inherited[js.ServerSettings, akka.http.scaladsl.settings.ServerSettings]
  implicit object PreviewServerSettings extends Inherited[js.PreviewServerSettings, akka.http.scaladsl.settings.PreviewServerSettings]
  implicit object ServerSettingsT extends Inherited[js.ServerSettings.Timeouts, akka.http.scaladsl.settings.ServerSettings.Timeouts]
  implicit object Http2ServerSettingT extends Inherited[js.Http2ServerSettings, akka.http.scaladsl.settings.Http2ServerSettings]
  implicit object PoolImplementationT extends Inherited[js.PoolImplementation, akka.http.scaladsl.settings.PoolImplementation]

  implicit object OutgoingConnection extends JavaMapping[jdsl.OutgoingConnection, sdsl.Http.OutgoingConnection] {
    def toScala(javaObject: jdsl.OutgoingConnection): sdsl.Http.OutgoingConnection = javaObject.delegate
    def toJava(scalaObject: sdsl.Http.OutgoingConnection): jdsl.OutgoingConnection = new jdsl.OutgoingConnection(scalaObject)
  }
  implicit object ClientTransport extends JavaMapping[jdsl.ClientTransport, sdsl.ClientTransport] {
    def toScala(javaObject: jdsl.ClientTransport): sdsl.ClientTransport = jdsl.ClientTransport.toScala(javaObject)
    def toJava(scalaObject: sdsl.ClientTransport): jdsl.ClientTransport = jdsl.ClientTransport.fromScala(scalaObject)
  }
}
