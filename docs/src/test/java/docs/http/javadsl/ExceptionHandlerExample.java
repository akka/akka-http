/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

//#explicit-handler-example

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.ExceptionHandler;
import akka.http.javadsl.server.PathMatchers;
import akka.http.javadsl.server.Route;

import java.util.concurrent.CompletionStage;

import static akka.http.javadsl.server.PathMatchers.integerSegment;

public class ExceptionHandlerExample extends AllDirectives {
  public static void main(String[] args) {
    final ActorSystem system = ActorSystem.create();
    final Http http = Http.get(system);

    final ExceptionHandlerExample app = new ExceptionHandlerExample();

    final CompletionStage<ServerBinding> binding = http.newServerAt("localhost", 8080).bind(app.createRoute());
  }


  public Route createRoute() {
    final ExceptionHandler divByZeroHandler = ExceptionHandler.newBuilder()
      .match(ArithmeticException.class, x ->
        complete(StatusCodes.BAD_REQUEST, "You've got your arithmetic wrong, fool!"))
      .build();

    return path(PathMatchers.segment("divide").slash(integerSegment()).slash(integerSegment()), (a, b) ->
      handleExceptions(divByZeroHandler, () -> complete("The result is " + (a / b)))
    );
  }
}
//#explicit-handler-example
