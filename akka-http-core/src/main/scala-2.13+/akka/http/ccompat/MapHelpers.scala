/*
 * Copyright (C) 2018-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.ccompat

object MapHelpers {
  def convertMapToScala[K, V](jmap: java.util.Map[K, V]): scala.collection.immutable.Map[K, V] = {
    import scala.jdk.CollectionConverters._
    Map.empty.concat(jmap.asScala)
  }
}
