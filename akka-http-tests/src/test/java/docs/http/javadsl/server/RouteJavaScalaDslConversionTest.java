/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Directives;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.directives.RouteAdapter;
import akka.http.scaladsl.server.RequestContext;
import akka.http.scaladsl.server.RouteResult;
import scala.Function1;
import scala.concurrent.Future;

public class RouteJavaScalaDslConversionTest {

  void scalaToJava() {
    //#scala-to-java
    scala.Function1<
        akka.http.scaladsl.server.RequestContext,
        scala.concurrent.Future<akka.http.scaladsl.server.RouteResult>> scalaRoute = someRoute();

    akka.http.javadsl.server.Route javaRoute =
        RouteAdapter.asJava(scalaRoute);
    //#scala-to-java
  }

  void javaToScala() {
    //#java-to-scala
    Route javaRoute = Directives.get(() ->
        Directives.complete("okey")
    );

    scala.Function1<RequestContext, Future<RouteResult>> scalaRoute =
        javaRoute.asScala();
    //#java-to-scala
  }

  private Function1<RequestContext, Future<RouteResult>> someRoute() {
    return null;
  }


}
