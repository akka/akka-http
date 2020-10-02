/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.testkit;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Scheduler;
import akka.actor.typed.javadsl.AskPattern;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.http.scaladsl.model.StatusCodes;
import akka.japi.pf.PFBuilder;

import java.time.Duration;

public class MyAppWithActor extends AllDirectives {
  public static class Ping {
    public final ActorRef<String> replyTo;
    public Ping(ActorRef<String> replyTo) {
      this.replyTo = replyTo;
    }
  }

    public Route createRoute(ActorRef<Ping> actorRef, Scheduler scheduler) {
      Duration timeout = Duration.ofSeconds(3);
      return
        path("ping", () ->
          onSuccess(AskPattern.ask(actorRef, Ping::new, timeout, scheduler), result ->
            complete(result)
          )
        );
    }

}
