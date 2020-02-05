/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import java.util.function.Supplier

import akka.http.javadsl.server.Route
import org.scalatest.wordspec.AnyWordSpec

class RouteJavaScalaDslConversionSpec extends AnyWordSpec {

  "Routes" must {

    "convert JavaDSL to ScalaDSL" in {
      //#java-to-scala
      val javaRoute =
        akka.http.javadsl.server.Directives.get(new Supplier[akka.http.javadsl.server.Route] {
          override def get(): Route = akka.http.javadsl.server.Directives.complete("ok")
        })

      // Remember that Route in Scala is just a type alias:
      //   type Route = RequestContext => Future[RouteResult]
      val scalaRoute: akka.http.scaladsl.server.Route = javaRoute.asScala
      //#java-to-scala
    }

    "convert ScalaDSL to JavaDSL" in {
      //#scala-to-java
      val scalaRoute: akka.http.scaladsl.server.Route =
        akka.http.scaladsl.server.Directives.get {
          akka.http.scaladsl.server.Directives.complete("OK")
        }

      val javaRoute: akka.http.javadsl.server.Route =
        akka.http.javadsl.server.directives.RouteAdapter.asJava(scalaRoute)
      //#scala-to-java
    }
  }
}
