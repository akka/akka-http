/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.util.{ Map ⇒ JMap, List ⇒ JList }
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Optional
import java.util.function.{ Function ⇒ JFunction }

import akka.http.javadsl.unmarshalling.Unmarshaller

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._

import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.directives.{ AnyParamDirectives ⇒ D }
import akka.http.scaladsl.server.directives.ParameterDirectives._
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers._

abstract class AnyParamDirectives extends FramedEntityStreamingDirectives {

  def anyParam(name: String, inner: java.util.function.Function[String, Route]): Route = RouteAdapter(
    D.anyParam(name) { value ⇒
      inner.apply(value).delegate
    })

  @CorrespondsTo("anyParam")
  def anyParamOptional(name: String, inner: java.util.function.Function[Optional[String], Route]): Route = RouteAdapter(
    D.anyParam(name.?) { value ⇒
      inner.apply(value.asJava).delegate
    })

  @CorrespondsTo("anyParams")
  def anyParamsOptional(name: String, inner: java.util.function.Function[Optional[String], Route]): Route =
    anyParamOptional(name, inner)

  @CorrespondsTo("anyParamSeq")
  def anyParamList(name: String, inner: java.util.function.Function[java.util.List[String], Route]): Route = RouteAdapter(
    D.anyParam(_string2NR(name).*) { values ⇒
      inner.apply(values.toSeq.asJava).delegate
    })

  def anyParam[T](t: Unmarshaller[String, T], name: String, inner: java.util.function.Function[T, Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.anyParam(name.as[T]) { value ⇒
        inner.apply(value).delegate
      })
  }

  @CorrespondsTo("anyParam")
  def anyParamOptional[T](t: Unmarshaller[String, T], name: String, inner: java.util.function.Function[Optional[T], Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.anyParam(name.as[T].?) { value ⇒
        inner.apply(value.asJava).delegate
      })
  }

  @CorrespondsTo("anyParams")
  def anyParamsOptional[T](t: Unmarshaller[String, T], name: String, inner: java.util.function.Function[Optional[T], Route]): Route =
    anyParamOptional(t, name, inner)

  @CorrespondsTo("anyParam")
  def anyParamOrDefault[T](t: Unmarshaller[String, T], defaultValue: T, name: String, inner: java.util.function.Function[T, Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.anyParam(name.as[T].?(defaultValue)) { value ⇒
        inner.apply(value).delegate
      })
  }

  @CorrespondsTo("anyParams")
  def anyParamsOrDefault[T](t: Unmarshaller[String, T], defaultValue: T, name: String, inner: java.util.function.Function[T, Route]): Route =
    anyParamOrDefault(t, defaultValue, name, inner)

  @CorrespondsTo("anyParamSeq")
  def anyParamList[T](t: Unmarshaller[String, T], name: String, inner: java.util.function.Function[java.util.List[T], Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.anyParam(name.as[T].*) { values ⇒
        inner.apply(values.toSeq.asJava).delegate
      })
  }

  def anyParamMap(inner: JFunction[JMap[String, String], Route]): Route = RouteAdapter {
    D.anyParamMap { map ⇒ inner.apply(map.asJava).delegate }
  }

  def anyParamMultiMap(inner: JFunction[JMap[String, JList[String]], Route]): Route = RouteAdapter {
    D.anyParamMultiMap { map ⇒ inner.apply(map.mapValues { l ⇒ l.asJava }.asJava).delegate }
  }

  @CorrespondsTo("anyParamSeq")
  def anyParamList(inner: JFunction[JList[JMap.Entry[String, String]], Route]): Route = RouteAdapter {
    D.anyParamSeq { list ⇒
      val entries: Seq[JMap.Entry[String, String]] = list.map { e ⇒ new SimpleImmutableEntry(e._1, e._2) }
      inner.apply(entries.asJava).delegate
    }
  }

}
