/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server;

import akka.Done;
import akka.actor.ActorSystem;
import akka.http.javadsl.ServerBinding;
import akka.http.scaladsl.Http;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

/**
 * Deprecated: See akka.http.javadsl.server.HttpApp
 */
@Deprecated
public class MinimalHttpApp extends HttpApp {

  CompletableFuture<Done> shutdownTrigger = new CompletableFuture<>();
  CompletableFuture<ServerBinding> bindingPromise = new CompletableFuture<>();


  public void shutdown() {
    shutdownTrigger.complete(Done.getInstance());
  }
    
  @Override
  protected Route routes() {
    return path("foo", () ->
        complete("bar")
      );
  }

  @Override
  protected void postHttpBinding(ServerBinding binding) {
    super.postHttpBinding(binding);
    bindingPromise.complete(binding);
  }

  @Override
  protected void postHttpBindingFailure(Throwable cause) {
    super.postHttpBindingFailure(cause);
    bindingPromise.completeExceptionally(cause);
  }

  @Override
  protected CompletionStage<Done> waitForShutdownSignal(ActorSystem system) {
    return shutdownTrigger;
  }
}
