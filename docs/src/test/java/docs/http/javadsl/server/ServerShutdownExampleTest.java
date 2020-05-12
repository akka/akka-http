package docs.http.javadsl.server;

import akka.actor.CoordinatedShutdown;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Behaviors;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.server.Route;
import akka.stream.Materializer;
import akka.stream.SystemMaterializer;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public class ServerShutdownExampleTest {

    public void mountCoordinatedShutdown() {
        ActorSystem<?> system = ActorSystem.create(Behaviors.empty(), "http-server");
        Materializer materializer = SystemMaterializer.get(system).materializer();

        Route routes = null;

        // #suggested
        CompletionStage<ServerBinding> bindingFuture = Http
            .get(system)
            .bindAndHandle(routes.flow(system), ConnectHttp.toHost("localhost", 8080), materializer)
            .thenApply(binding -> binding.addToCoordinatedShutdown(Duration.ofSeconds(10), system));
        // #suggested

        bindingFuture.exceptionally(cause -> {
            system.log().error("Error starting the server: " + cause.getMessage(), cause);
            return null;
        });

        // #shutdown
        final class UserInitiatedShutdown implements CoordinatedShutdown.Reason {
            @Override
            public String toString() {
                return "UserInitiatedShutdown";
            }
        }

        CoordinatedShutdown.get(system).run(new UserInitiatedShutdown());
        // #shutdown
    }
}