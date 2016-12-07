/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.http.management

import com.typesafe.config.Config

final class ClusterHttpManagementSettings(val config: Config) {
  private val cc = config.getConfig("akka.http.cluster.management")
  val ClusterHttpManagementPort = cc.getInt("port")
  val ClusterHttpManagementHostname = cc.getString("hostname")
}
