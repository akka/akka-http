/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.AttributeKey;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.ResponseFuture;
import akka.http.javadsl.model.headers.AcceptEncoding;
import akka.http.javadsl.model.headers.HttpEncodings;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.SystemMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.SourceQueueWithComplete;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** A small example app that shows how to use the HTTP/2 client API currently against actual internet servers
 * Mirroring the akka.https.scaladsl counterpart
 *
 * NOTE requires Akka > 2.5.31 to run on JDK 11
 */
public class Http2ClientApp {

  public static void main(String[] args) {
    Config config =
        ConfigFactory.parseString(
            "#akka.loglevel = debug\n" +
               "akka.http.client.http2.log-frames = true\n" +
               "akka.http.client.parsing.max-content-length = 20m"
        ).withFallback(ConfigFactory.load());

    ActorSystem system = ActorSystem.create("Http2ClientApp", config);
    Materializer mat = SystemMaterializer.get(system).materializer();

    // #response-future-association
    Function<HttpRequest, CompletionStage<HttpResponse>> dispatch =
        singleRequest(system, Http.get(system).connectionTo("doc.akka.io").http2());

    dispatch.apply(
        HttpRequest.create(
            "https://doc.akka.io/api/akka/current/akka/actor/typed/scaladsl/index.html").withHeaders(
            Arrays.asList(AcceptEncoding.create(HttpEncodings.GZIP))
        )
    ).thenAccept(res -> {
      System.out.println("[1] Got index.html: " + res);
      res.entity().getDataBytes().runWith(Sink.ignore(), mat)
          .thenAccept(consumedRes -> System.out.println("Finished reading [1] " + consumedRes));
    });

    // #response-future-association
    dispatch.apply(HttpRequest.create("https://doc.akka.io/api/akka/current/index.js"))
        .thenAccept(res -> {
          System.out.println("[2] Got index.js: " + res);
          res.entity().getDataBytes().runWith(Sink.ignore(), mat)
            .thenAccept(consumedRes -> System.out.println("Finished reading [2] " + res));
        });
    dispatch.apply(HttpRequest.create("https://doc.akka.io/api/akka/current/lib/MaterialIcons-Regular.woff"))
        .thenCompose(res -> res.toStrict(1000, system))
        .thenAccept(res -> System.out.println("[3] Got font: " + res));
    dispatch.apply(HttpRequest.create("https://doc.akka.io/favicon.ico"))
        .thenCompose(res -> res.toStrict(1000, system))
        .thenAccept(res -> System.out.println("[4] Got favicon: " + res));
  }

  // #response-future-association
  private static Function<HttpRequest, CompletionStage<HttpResponse>> singleRequest(ActorSystem system, Flow<HttpRequest, HttpResponse, ?> connection) {
    SourceQueueWithComplete<HttpRequest> queue =
        Source.<HttpRequest>queue(100, OverflowStrategy.dropNew())
            .via(connection)
            .to(Sink.foreach(res -> {
              try {
                // complete the future with the response when it arrives
                ResponseFuture responseFuture = res.getAttribute(ResponseFuture.KEY()).get();
                responseFuture.future().complete(res);
              } catch (Exception ex) {
                ex.printStackTrace();
              }
            }))
        .run(SystemMaterializer.get(system).materializer());

    return (HttpRequest req) -> {
      // create a future of the response for each request and set it as an attribute on the request
      CompletableFuture<HttpResponse> future = new CompletableFuture<>();
      ResponseFuture attribute = new ResponseFuture(future);
      return queue.offer(req.addAttribute(ResponseFuture.KEY(), attribute))
          // return the future response
          .thenCompose(__ -> attribute.future());
    };
  }
  // #response-future-association
}
