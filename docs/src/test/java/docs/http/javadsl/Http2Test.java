/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
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

//#bindAndHandleAsync
import akka.http.javadsl.Http;
import static akka.http.javadsl.ConnectHttp.toHostHttps;

//#bindAndHandleAsync

//#bindAndHandleRaw
import akka.http.javadsl.UseHttp2;
import static akka.http.javadsl.ConnectHttp.toHost;

//#bindAndHandleRaw

class Http2Test {
  void testBindAndHandleAsync() {
    Function<HttpRequest, CompletionStage<HttpResponse>> asyncHandler = r -> CompletableFuture.completedFuture(HttpResponse.create());
    ActorSystem system = ActorSystem.create();
    Materializer materializer = ActorMaterializer.create(system);
    HttpsConnectionContext httpsConnectionContext = null;

    //#bindAndHandleAsync
    Http.get(system)
      .bindAndHandleAsync(
        asyncHandler,
        toHostHttps("127.0.0.1", 8443).withCustomHttpsContext(httpsConnectionContext),
        materializer);
    //#bindAndHandleAsync

    //#bindAndHandleRaw
    Http.get(system)
      .bindAndHandleAsync(
        asyncHandler,
        toHost("127.0.0.1", 8080, UseHttp2.always()),
        materializer);
    //#bindAndHandleRaw
  }
}
