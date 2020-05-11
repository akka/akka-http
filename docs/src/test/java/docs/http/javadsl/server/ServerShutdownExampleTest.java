package docs.http.javadsl.server;

import akka.Done;
import akka.actor.ClassicActorSystemProvider;
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

    // #suggested
    public ServerBinding addToCoordinatedShutdown(Duration terminationGracePeriod, ServerBinding binding, ClassicActorSystemProvider system) {
        CoordinatedShutdown shutdown = CoordinatedShutdown.get(system);
        shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind(), "http-unbind-" + binding.localAddress(), binding::unbind);
        shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone(), "http-terminate-" + binding.localAddress(), () ->
            binding.terminate(terminationGracePeriod).thenApply(termination -> Done.done())
        );
        shutdown.addTask(CoordinatedShutdown.PhaseServiceStop(), "http-shutdown-" + binding.localAddress(), () ->
            Http.get(system).shutdownAllConnectionPools().thenApply(termination -> Done.done())
        );
        return binding;
    }
    // #suggested


    public void mountCoordinatedShutdown() {
        ActorSystem<?> system = ActorSystem.create(Behaviors.empty(), "http-server");
        Materializer materializer = SystemMaterializer.get(system).materializer();

        Route routes = null;

        // #suggested

        // ...

        CompletionStage<ServerBinding> bindingFuture = Http
            .get(system)
            .bindAndHandle(routes.flow(system), ConnectHttp.toHost("localhost", 8080), materializer)
            .thenApply(binding -> addToCoordinatedShutdown(Duration.ofSeconds(10), binding, system));
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
