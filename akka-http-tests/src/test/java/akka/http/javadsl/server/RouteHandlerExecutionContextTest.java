/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.server;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.japi.function.Function;
import akka.stream.Materializer;
import akka.stream.SystemMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.javadsl.TestKit;
import org.junit.Test;
import scala.concurrent.ExecutionContextExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RouteHandlerExecutionContextTest {

  @Test
  public void usesGivenExecutionContextAndMaterializerInsteadOfSystemDefaults() throws Exception {
    ActorSystem system = ActorSystem.create("RouteHandlerExecutionContextTest");
    try {
      // Route completes asynchronously (via a not-yet-resolved CompletableFuture) so that the
      // final RouteResult -> HttpResponse mapping is actually scheduled on an ExecutionContext,
      // rather than run inline as FastFuture does for already-completed Futures.
      CompletableFuture<String> promise = new CompletableFuture<>();
      Route route = Directives.onSuccess(promise, Directives::complete);

      AtomicInteger submissionCount = new AtomicInteger(0);
      ExecutionContextExecutor delegate = system.dispatcher();
      ExecutionContextExecutor countingEc =
          new ExecutionContextExecutor() {
            @Override
            public void execute(Runnable runnable) {
              submissionCount.incrementAndGet();
              delegate.execute(runnable);
            }

            @Override
            public void reportFailure(Throwable cause) {
              delegate.reportFailure(cause);
            }
          };
      Materializer materializer = SystemMaterializer.get(system).materializer();

      Function<HttpRequest, CompletionStage<HttpResponse>> handler =
          route.handler(system, countingEc, materializer);

      CompletionStage<HttpResponse> responseFuture = handler.apply(HttpRequest.create());
      promise.complete("ok");
      HttpResponse response = responseFuture.toCompletableFuture().get();

      assertEquals(StatusCodes.OK, response.status());
      assertTrue("expected the given ExecutionContext to be used", submissionCount.get() > 0);
    } finally {
      TestKit.shutdownActorSystem(system);
    }
  }

  @Test
  public void flowUsesGivenMaterializerInsteadOfSystemDefault() throws Exception {
    ActorSystem system = ActorSystem.create("RouteHandlerExecutionContextTestFlow");
    try {
      Materializer givenMaterializer = Materializer.createMaterializer(system);
      AtomicReference<Materializer> observedMaterializer = new AtomicReference<>();
      Route route =
          Directives.extractMaterializer(
              mat -> {
                observedMaterializer.set(mat);
                return Directives.complete("ok");
              });

      akka.stream.javadsl.Flow<HttpRequest, HttpResponse, NotUsed> flow =
          route.flow(system, givenMaterializer);

      HttpResponse response =
          Source.single(HttpRequest.create())
              .via(flow)
              .runWith(Sink.head(), SystemMaterializer.get(system).materializer())
              .toCompletableFuture()
              .get();

      assertEquals(StatusCodes.OK, response.status());
      assertSame(
          "expected the given Materializer to be used for the route, not the SystemMaterializer",
          givenMaterializer,
          observedMaterializer.get());
    } finally {
      TestKit.shutdownActorSystem(system);
    }
  }
}
