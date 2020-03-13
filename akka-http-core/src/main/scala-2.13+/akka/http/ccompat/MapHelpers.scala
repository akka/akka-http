/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.ccompat

object MapHelpers {
  def convertMapToScala[K, V](jmap: java.util.Map[K, V]): scala.collection.immutable.Map[K, V] = {
    import scala.collection.JavaConverters._
    Map.empty.concat(jmap.asScala)
  }
}
