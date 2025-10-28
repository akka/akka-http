/*
 * Copyright (C) 2017-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.impl.settings

final case class HostConnectionPoolSetup(host: String, port: Int, setup: ConnectionPoolSetup)

