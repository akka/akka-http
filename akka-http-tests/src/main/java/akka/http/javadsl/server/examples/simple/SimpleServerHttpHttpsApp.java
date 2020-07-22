/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.simple;

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.server.Route;

import static akka.http.javadsl.server.Directives.*;

import java.io.IOException;

public class SimpleServerHttpHttpsApp {

  public Route createRoute() {
    return get( () -> complete("Hello World!") );
  }

  // ** STARTING THE SERVER ** //

  public static void main(String[] args) throws IOException {
    final ActorSystem system = ActorSystem.create("SimpleServerHttpHttpsApp");

    final SimpleServerApp app = new SimpleServerApp();
    final Route route = app.createRoute();

    //#both-https-and-http
    final Http http = Http.get(system);
    //Run HTTP server firstly
    http.newServerAt("localhost", 80).bind(route);

    //get configured HTTPS context
    HttpsConnectionContext https = SimpleServerApp.createHttpsContext(system);

    // sets default context to HTTPS – all Http() bound servers for this ActorSystem will use HTTPS from now on
    http.setDefaultServerHttpContext(https);

    //Then run HTTPS server
    http.newServerAt("localhost", 443).enableHttps(https).bind(route);
    //#both-https-and-http

    System.out.println("Type RETURN to exit");
    System.in.read();
    system.terminate();
  }
}
