/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.testkit;

//#simple-app

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.examples.simple.SimpleServerApp;
import akka.http.javadsl.unmarshalling.StringUnmarshallers;

import java.io.IOException;

public class MyAppService extends AllDirectives {

  public String add(double x, double y) {
    return "x + y = " + (x + y);
  }

  public Route createRoute() {
    return
      get(() ->
        pathPrefix("calculator", () ->
          path("add", () ->
            parameter(StringUnmarshallers.DOUBLE, "x", x ->
              parameter(StringUnmarshallers.DOUBLE, "y", y ->
                complete(add(x, y))
              )
            )
          )
        )
      );
  }

  public static void main(String[] args) throws IOException {
    final ActorSystem system = ActorSystem.create();

    final SimpleServerApp app = new SimpleServerApp();

    Http.get(system).newServerAt("127.0.0.1", 8080).bind(app.createRoute());

    System.console().readLine("Type RETURN to exit...");
    system.terminate();
  }
}
//#simple-app
