/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import java.util.concurrent.atomic.AtomicLong

import akka.http.impl.engine.client.PoolId.PoolUsage
import akka.http.impl.settings.HostConnectionPoolSetup

/**
 * Represents a pool by its target endpoint and settings and its designated usage (shared or unique).
 *
 * A [[PoolId]] is represented by its [[HostConnectionPoolSetup]] and its [[PoolUsage]]. If the later
 * is [[SharedPool]], it means that a shared pool must be used for this particular [[HostConnectionPoolSetup]].
 */
private[http] final class PoolId(val hcps: HostConnectionPoolSetup, val usage: PoolUsage) {
  override def toString = s"PoolId(hcps = $hcps, usage = $usage)"

  override def equals(that: Any): Boolean =
    that match {
      case p: PoolId => p.hcps == hcps && p.usage == usage
      case _         => false
    }

  override def hashCode(): Int = hcps.hashCode() ^ usage.hashCode()
}

private[http] object PoolId {

  sealed trait PoolUsage {
    def name: String
  }
  case object SharedPool extends PoolUsage {
    def name: String = "shared"
  }
  final case class UniquePool(id: Long) extends PoolUsage {
    def name: String = s"#$id"
  }

  private[this] val uniquePoolId = new AtomicLong(0)
  def newUniquePool() = UniquePool(uniquePoolId.incrementAndGet())
}
