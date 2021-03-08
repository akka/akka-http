/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.metrics

/**
 * A collection of metrics statistics that can be sent as a single system event.
 */
case class HttpMeasurements private (items: collection.immutable.Seq[HttpMetric.Measurement])
object HttpMeasurements {
  def apply(items: HttpMetric.Measurement*) = new HttpMeasurements(items.toVector)
}
