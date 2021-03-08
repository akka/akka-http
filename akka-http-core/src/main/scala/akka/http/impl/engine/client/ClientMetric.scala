/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.http.metrics.HttpMetric

/**
 * Factory methods to construct HttpMetric instances with preset values for http client monitoring.
 */
private[client] case class ClientMetric(host: String, port: Int) {
  def gauge(name: String) =
    HttpMetric.Gauge("http.client." + name, Map("host" → host, "port" → port.toString))
}
