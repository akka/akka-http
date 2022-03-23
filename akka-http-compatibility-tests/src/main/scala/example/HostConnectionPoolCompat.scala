/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package example

import akka.http.scaladsl.Http.HostConnectionPool

object HostConnectionPoolCompat {
  def access(hcp: HostConnectionPool): Unit = {
    val theSetup = hcp.setup
    hcp.shutdown()

    hcp match {
      // This still works because case class matching does not require an unapply method
      case HostConnectionPool(setup) => require(setup == theSetup)
    }

    require(hcp.productArity == 1)
    require(hcp.productElement(0) == hcp.setup)
    require(hcp.canEqual(hcp))
    require(hcp.equals(hcp))

    // Companion object is still there, even if had no good public use
    HostConnectionPool

    // This one didn't compile even before (private[http] constructor)
    // new HostConnectionPool(hcp.setup)(null)

    // This line compiles but only by giving null as the PoolGateway parameter which was private before
    // This would crash now
    // HostConnectionPool(hcp.setup)(null)

    // These lines compile but require nulling out the gateway parameter
    // They would crash now.
    // hcp.copy(setup = hcp.setup)(null)
    // hcp.copy()(null)

    // This compiles but crashes now but is unlikely user code
    // val Some(setup) = HostConnectionPool.unapply(hcp)
  }
}
