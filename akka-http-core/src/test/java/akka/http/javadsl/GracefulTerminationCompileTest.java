/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public class GracefulTerminationCompileTest {

    public static void main(String[] args) throws Exception {
        ActorSystem system = ActorSystem.create();
        Materializer materializer = ActorMaterializer.create(system);

        Http http = Http.get(system);

        Function<HttpRequest, CompletionStage<HttpResponse>> handle =
                (req) -> CompletableFuture.completedFuture(HttpResponse.create());
        CompletionStage<ServerBinding> bound = http.bindAndHandleAsync(handle, ConnectHttp.toHost("127.0.0.1"), system);

        ServerBinding serverBinding = bound.toCompletableFuture().get();
        CompletionStage<HttpTerminated> terminate = serverBinding.terminate(Duration.ofSeconds(1));
        CompletionStage<Duration> whenSignalled = serverBinding.whenTerminationSignalIssued();
        CompletionStage<HttpTerminated> whenTerminated = serverBinding.whenTerminated();
    }
}
