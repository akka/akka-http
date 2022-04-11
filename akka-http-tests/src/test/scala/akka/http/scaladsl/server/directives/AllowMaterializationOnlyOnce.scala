/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import java.util.concurrent.atomic.AtomicBoolean

import akka.NotUsed
import akka.stream.scaladsl.Flow

object AllowMaterializationOnlyOnce {
  def apply[T, Mat](): Flow[T, T, NotUsed] = {
    val materialized = new AtomicBoolean(false)
    Flow[T].mapMaterializedValue { mat =>
      if (materialized.compareAndSet(false, true)) {
        mat
      } else {
        throw new IllegalStateException("Substream Source cannot be materialized more than once")
      }
    }
  }
}
