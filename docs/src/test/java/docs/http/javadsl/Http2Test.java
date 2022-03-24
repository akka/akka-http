/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import akka.actor.ActorSystem;
import akka.http.javadsl.HttpsConnectionContext;
//#trailingHeaders
import akka.http.javadsl.model.Trailer;
import akka.http.javadsl.model.headers.RawHeader;
import static akka.http.javadsl.model.AttributeKeys.trailer;

//#trailingHeaders
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.function.Function;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;

//#bindAndHandleSecure
//#bindAndHandlePlain
//#http2Client
//#http2ClientWithPriorKnowledge
import akka.http.javadsl.Http;

//#http2ClientWithPriorKnowledge
//#http2Client
//#bindAndHandlePlain
//#bindAndHandleSecure

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

    //#http2Client
    Http.get(system)
            .connectionTo("127.0.0.1")
            .toPort(8443)
            .http2();
    //#http2Client

    //#http2ClientWithPriorKnowledge
    Http.get(system)
            .connectionTo("127.0.0.1")
            .toPort(8080)
            .http2WithPriorKnowledge();
    //#http2ClientWithPriorKnowledge

    //#trailingHeaders
    HttpResponse.create()
            .withStatus(200)
            .addAttribute(trailer, Trailer.create().addHeader(RawHeader.create("name", "value")));
    //#trailingHeaders
  }
}
