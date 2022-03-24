/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.function.Function;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class GracefulTerminationCompileTest {

    public static void main(String[] args) throws Exception {
        ActorSystem system = ActorSystem.create();

        Http http = Http.get(system);

        Function<HttpRequest, CompletionStage<HttpResponse>> handle =
                (req) -> CompletableFuture.completedFuture(HttpResponse.create());
        CompletionStage<ServerBinding> bound = http.newServerAt("127.0.0.1", 0).bind(handle);

        ServerBinding serverBinding = bound.toCompletableFuture().get();
        CompletionStage<HttpTerminated> terminate = serverBinding.terminate(Duration.ofSeconds(1));
        CompletionStage<Duration> whenSignalled = serverBinding.whenTerminationSignalIssued();
        CompletionStage<HttpTerminated> whenTerminated = serverBinding.whenTerminated();
    }
}
