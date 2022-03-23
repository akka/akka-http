/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import java.util.concurrent.CompletionStage

import akka.Done
import akka.annotation.DoNotInherit
import akka.http.impl.settings.HostConnectionPoolSetup

@DoNotInherit
abstract class HostConnectionPool private[http] {
  def setup: HostConnectionPoolSetup

  /**
   * Asynchronously triggers the shutdown of the host connection pool.
   *
   * The produced [[CompletionStage]] is fulfilled when the shutdown has been completed.
   */
  def shutdown(): CompletionStage[Done]
}
