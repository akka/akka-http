/*
 * Copyright (C) 2018-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.ccompat

/**
 * INTERNAL API
 */
object MapHelpers {
  def convertMapToScala[K, V](jmap: java.util.Map[K, V]): scala.collection.immutable.Map[K, V] = {
    import scala.jdk.CollectionConverters._
    Map.empty ++ jmap.asScala
  }
}
