/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import akka.actor.ActorSystem;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.function.Function;
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
      .newServerAt("127.0.0.1", 8443)
      .enableHttps(httpsConnectionContext)
      .bind(asyncHandler);
    //#bindAndHandleSecure

    //#bindAndHandlePlain
    Http.get(system)
      .newServerAt("127.0.0.1", 8443)
      .bind(asyncHandler);
    //#bindAndHandlePlain
  }
}
