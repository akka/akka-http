/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.ccompat

/**
 * INTERNAL API
 */
object MapHelpers {
  def convertMapToScala[K, V](jmap: java.util.Map[K, V]): scala.collection.immutable.Map[K, V] = {
    import scala.collection.JavaConverters._
    Map.empty ++ jmap.asScala
  }
}
