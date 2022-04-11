/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.impl.util.ExampleHttpContexts;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.function.Function;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class Http2JavaServerTest {
  public static void main(String[] args) {
    Config testConf = ConfigFactory.parseString(
      "akka.loglevel = INFO\n" +
      "akka.log-dead-letters = off\n" +
      "akka.stream.materializer.debug.fuzzing-mode = off\n" +
      "akka.actor.serialize-creators = off\n" +
      "akka.actor.serialize-messages = off\n" +
      "#akka.actor.default-dispatcher.throughput = 1000\n" +
      "akka.actor.default-dispatcher.fork-join-executor.parallelism-max=8\n" +
      "akka.http.server.preview.enable-http2 = on\n"
    );
    ActorSystem system = ActorSystem.create("ServerTest", testConf);

    Function<HttpRequest, CompletionStage<HttpResponse>> handler =
      request -> CompletableFuture.completedFuture(HttpResponse.create().withEntity(request.entity()));

    HttpsConnectionContext httpsConnectionContext = ExampleHttpContexts.getExampleServerContext();

    Http.get(system).newServerAt("localhost", 9001)
        .enableHttps(httpsConnectionContext)
        .bind(handler);

    // TODO what about unencrypted http2?
  }
}
