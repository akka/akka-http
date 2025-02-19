/*
 * Copyright (C) 2017-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.caching

import akka.annotation.InternalApi
import akka.http.caching.javadsl.Cache
import akka.http.impl.util.JavaMapping

/** INTERNAL API */
@InternalApi
private[akka] object CacheJavaMapping {

  def cacheMapping[JK, JV, SK <: JK, SV <: JV] =
    new JavaMapping[Cache[JK, JV], akka.http.caching.scaladsl.Cache[SK, SV]] {
      def toScala(javaObject: Cache[JK, JV]): akka.http.caching.scaladsl.Cache[SK, SV] =
        javaObject.asInstanceOf[akka.http.caching.scaladsl.Cache[SK, SV]]

      def toJava(scalaObject: akka.http.caching.scaladsl.Cache[SK, SV]): Cache[JK, JV] =
        scalaObject.asInstanceOf[Cache[JK, JV]]
    }

  object Implicits {

    implicit object CachingSettings extends JavaMapping.Inherited[javadsl.CachingSettings, scaladsl.CachingSettings]
    implicit object LfuCacheSettings extends JavaMapping.Inherited[javadsl.LfuCacheSettings, scaladsl.LfuCacheSettings]
  }
}
