/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.model.AttributeKey;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
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

import java.time.Duration;
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
        ).withFallback(ConfigFactory.defaultApplication());

    ActorSystem system = ActorSystem.create("Http2ClientApp", config);
    Materializer mat = SystemMaterializer.get(system).materializer();

    Function<HttpRequest, CompletionStage<HttpResponse>> dispatch =
        singleRequest(system, Http.get(system).connectionTo("doc.akka.io").unorderedFlow());

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

  // FIXME provide this out of hte box perhaps
  public static final class ResponseFuture {
    public static final AttributeKey<ResponseFuture> KEY =
        AttributeKey.create("association-handle", ResponseFuture.class);
    final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
  }

  private static Function<HttpRequest, CompletionStage<HttpResponse>> singleRequest(ActorSystem system, Flow<HttpRequest, HttpResponse, ?> connection) {
    SourceQueueWithComplete<HttpRequest> queue =
        Source.<HttpRequest>queue(100, OverflowStrategy.dropNew())
            .via(connection)
            .to(Sink.foreach(res -> {
              // FIXME Java model does not have/give us the response here so this does not currently work
              res.getAttribute(ResponseFuture.KEY).get().future.complete(res);
            }))
        .run(SystemMaterializer.get(system).materializer());

    return (HttpRequest req) -> {
      ResponseFuture attribute = new ResponseFuture();
      return queue.offer(req.addAttribute(ResponseFuture.KEY, attribute))
          .thenCompose(__ -> attribute.future);
    };
  }
}
