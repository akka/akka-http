/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import akka.actor.ActorSystem;
import akka.japi.Function;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;

//#bindAndHandleSecure
import akka.http.javadsl.Http;
import static akka.http.javadsl.ConnectHttp.toHostHttps;

//#bindAndHandleSecure

//#bindAndHandlePlain
import static akka.http.javadsl.ConnectHttp.toHost;

//#bindAndHandlePlain

class Http2Test {
  void testBindAndHandleAsync() {
    Function<HttpRequest, CompletionStage<HttpResponse>> asyncHandler = r -> CompletableFuture.completedFuture(HttpResponse.create());
    ActorSystem system = ActorSystem.create();
    Materializer materializer = ActorMaterializer.create(system);
    HttpsConnectionContext httpsConnectionContext = null;

    //#bindAndHandleSecure
    Http.get(system)
      .bindAndHandleAsync(
        asyncHandler,
        toHostHttps("127.0.0.1", 8443).withCustomHttpsContext(httpsConnectionContext),
        materializer);
    //#bindAndHandleSecure

    //#bindAndHandlePlain
    Http.get(system)
      .bindAndHandleAsync(
        asyncHandler,
        toHost("127.0.0.1", 8080),
        materializer);
    //#bindAndHandlePlain
  }
}
