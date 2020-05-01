/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

// Run with:
// sbt "docs/test:runMain docs.http.javadsl.HttpServerWithActorsSample"

// Example commands:
// curl -X POST -H "Content-Type: application/json" -d "{ \"id\": 42 }" localhost:8080/jobs
// curl localhost:8080/jobs/42

//#bootstrap
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.BehaviorBuilder;
import akka.actor.typed.javadsl.Behaviors;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.server.Route;
import akka.stream.SystemMaterializer;

import java.util.concurrent.CompletionStage;

public class HttpServerWithActorsSample {

  interface Message {}

  private static final class StartFailed implements Message {
    final Throwable ex;

    public StartFailed(Throwable ex) {
      this.ex = ex;
    }
  }

  private static final class Started implements Message {
    final ServerBinding binding;

    public Started(ServerBinding binding) {
      this.binding = binding;
    }
  }

  private static final class Stop implements Message {}

  public static Behavior<Message> create(String host, Integer port) {
    return Behaviors.setup(ctx -> {
      ActorSystem<Void> system = ctx.getSystem();
      ActorRef<JobRepository.Command> buildJobRepository = ctx.spawn(JobRepository.create(), "JobRepository");
      Route routes = new JobRoutes(buildJobRepository, ctx.getSystem()).jobRoutes();

      CompletionStage<ServerBinding> serverBinding =
              Http.get(ctx.getSystem()).bindAndHandle(
                      routes.flow(ctx.getSystem()),
                      ConnectHttp.toHost(host, port),
                      SystemMaterializer.get(system).materializer()
              );
      ctx.pipeToSelf(serverBinding, (binding, failure) -> {
        if (binding != null) return new Started(binding);
        else return new StartFailed(failure);
      });

      return starting(false);
    });
  }

  private static Behavior<Message> starting(boolean wasStopped) {
    return Behaviors.setup(ctx ->
            BehaviorBuilder.<Message>create()
                    .onMessage(StartFailed.class, failed -> {
                      throw new RuntimeException("Server failed to start", failed.ex);
                    })
                    .onMessage(Started.class, msg -> {
                      ctx.getLog().info(
                              "Server online at http://{}:{}",
                              msg.binding.localAddress().getAddress(),
                              msg.binding.localAddress().getPort());

                      if (wasStopped) ctx.getSelf().tell(new Stop());

                      return running(msg.binding);
                    })
                    .onMessage(Stop.class, s -> {
                      // we got a stop message but haven't completed starting yet,
                      // we cannot stop until starting has completed
                      return starting(true);
                    })
                    .build());
  }

  private static Behavior<Message> running(ServerBinding binding) {
    return BehaviorBuilder.<Message>create()
            .onMessage(Stop.class, msg -> Behaviors.stopped())
            .onSignal(PostStop.class, msg -> {
              binding.unbind();
              return Behaviors.same();
            })
            .build();
  }

  public static void main(String[] args) {
    ActorSystem<Message> system = ActorSystem.create(
            HttpServerWithActorsSample.create("localhost", 8080), "BuildJobsServer");
  }
}
//#bootstrap
