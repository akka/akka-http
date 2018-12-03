/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.ExceptionHandler;
import akka.http.javadsl.server.PathMatchers;
import akka.http.javadsl.server.RejectionHandler;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;

import java.util.concurrent.CompletionStage;

import static akka.http.javadsl.server.PathMatchers.integerSegment;

//#seal-handler-example
public class ExceptionHandlerInSealExample extends AllDirectives {
  public static void main(String[] args) {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer materializer = ActorMaterializer.create(system);
    final Http http = Http.get(system);

    final ExceptionHandlerInSealExample app = new ExceptionHandlerInSealExample();

    final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = app.createRoute().flow(system, materializer);
    final CompletionStage<ServerBinding> binding = http.bindAndHandle(routeFlow, ConnectHttp.toHost("localhost", 8080), materializer);
  }


  public Route createRoute() {
    final ExceptionHandler divByZeroHandler = ExceptionHandler.newBuilder()
      .match(ArithmeticException.class, x ->
        complete(StatusCodes.BAD_REQUEST, "You've got your arithmetic wrong, fool!"))
      .build();

    final RejectionHandler defaultHandler = RejectionHandler.defaultHandler();

    return path(PathMatchers.segment("divide").slash(integerSegment()).slash(integerSegment()), (a, b) ->
      complete("The result is " + (a / b))
    ).seal(defaultHandler, divByZeroHandler);
  }
}
//#seal-handler-example
