/*
 * Copyright (C) 2017-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

final case class HostConnectionPoolSetup(host: String, port: Int, setup: ConnectionPoolSetup)

