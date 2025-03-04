/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server;

import akka.Done;
import akka.actor.ActorSystem;
import akka.http.javadsl.ServerBinding;
import scala.runtime.BoxedUnit;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public class SneakHttpApp extends MinimalHttpApp {

  AtomicBoolean postServerShutdownCalled = new AtomicBoolean(false);
  AtomicBoolean postBindingCalled = new AtomicBoolean(false);
  AtomicBoolean postBindingFailureCalled = new AtomicBoolean(false);

  @Override
  protected void postServerShutdown(Optional<Throwable> failure, ActorSystem system) {
    postServerShutdownCalled.set(true);
  }

  @Override
  protected void postHttpBinding(ServerBinding binding) {
    postBindingCalled.set(true);
    bindingPromise.complete(binding);
  }

  @Override
  protected void postHttpBindingFailure(Throwable cause) {
    postBindingFailureCalled.set(true);
  }
}
