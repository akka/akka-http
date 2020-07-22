/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;

import java.util.concurrent.CompletionStage;

//#route-seal-example
public class RouteSealExample extends AllDirectives {

  public static void main(String [] args) {
    RouteSealExample app = new RouteSealExample();
    app.runServer();
  }

  public void runServer(){
    ActorSystem system = ActorSystem.create();

    Route sealedRoute = get(
      () -> pathSingleSlash( () ->
        complete("Captain on the bridge!")
      )
    ).seal();

    Route route = respondWithHeader(
      RawHeader.create("special-header", "you always have this even in 404"),
      () -> sealedRoute
    );

    final Http http = Http.get(system);
    final CompletionStage<ServerBinding> binding = http.newServerAt("localhost", 8080).bind(route);
  }
}
//#route-seal-example