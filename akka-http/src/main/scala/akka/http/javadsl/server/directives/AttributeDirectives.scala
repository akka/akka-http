/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.util.{ Optional, function => jf }

import akka.http.javadsl.model.AttributeKey
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.directives.{ AttributeDirectives => D }

import scala.compat.java8.OptionConverters._

abstract class AttributeDirectives extends HeaderDirectives {
  import akka.http.impl.util.JavaMapping._

  /**
   * Extracts the value of the request attribute with the given key.
   * If no attribute is found the request is rejected with a [[akka.http.javadsl.server.MissingAttributeRejection]].
   */
  def attribute[T](key: AttributeKey[T], inner: jf.Function[T, Route]) = RouteAdapter {
    D.attribute(toScala(key)) { value: T =>
      inner.apply(value).delegate
    }
  }

  /**
   * Extracts the value of the optional request attribute with the given key.
   */
  def optionalAttribute[T](key: AttributeKey[T], inner: jf.Function[Optional[T], Route]) = RouteAdapter {
    D.optionalAttribute(toScala(key)) { value =>
      inner.apply(value.asJava).delegate
    }
  }

}
