/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server;

//#binding-failure-high-level-example

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.server.Route;

import java.io.IOException;
import java.util.concurrent.CompletionStage;

public class HighLevelServerBindFailureExample {
  public static void main(String[] args) throws IOException {
    // boot up server using the route as defined below
    final ActorSystem system = ActorSystem.create();

    final HighLevelServerExample app = new HighLevelServerExample();
    final Route route = app.createRoute();

    final CompletionStage<ServerBinding> binding =
        Http.get(system).newServerAt("127.0.0.1", 8080).bind(route);

    binding.exceptionally(failure -> {
      System.err.println("Something very bad happened! " + failure.getMessage());
      system.terminate();
      return null;
    });

    system.terminate();
  }
}
//#binding-failure-high-level-example
