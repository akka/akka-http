/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi

/** INTERNAL API */
@InternalApi
final case class HostConnectionPoolSetup(host: String, port: Int, setup: ConnectionPoolSetup)

