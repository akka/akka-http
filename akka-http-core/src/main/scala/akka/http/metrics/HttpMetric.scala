/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.metrics

/**
 * A single aspect of akka-http that can be measured, e.g. the number of connections open to a specific host
 */
sealed trait HttpMetric {
  /**
   * A name for the metrics, as a dot-separated path, e.g. "http.pool.connections.idle"
   */
  def name: String
  /**
   * Arbitrary tags that further uniquely identify this metric, e.g. "host -> example.com".
   */
  def tags: Map[String, String]
}

object HttpMetric {
  /**
   * A measurement made by a particular HttpMetric.
   */
  sealed trait Measurement {
    def metric: HttpMetric
  }

  /**
   * A gauge records a continually varying measurement that can go up and down, typically between set minima and maxima.
   * Examples are number of open connections, percentage of CPU time used, etc.
   */
  case class Gauge(name: String, tags: Map[String, String]) extends HttpMetric {
    def apply(value: Long) = Gauge.Measurement(this, value)
  }
  object Gauge {
    case class Measurement(metric: Gauge, value: Long) extends HttpMetric.Measurement
  }
}

