/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;
//#minimal-routing-example
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;

import java.util.concurrent.CompletionStage;

public class HttpServerMinimalExampleTest extends AllDirectives {

    public static void main(String[] args) throws Exception {
        Behavior<Void> guardian = Behaviors.setup(actorContext -> {
            final ActorSystem<?> system = actorContext.getSystem();
            final Http http = Http.get(system);

            //In order to access all directives we need an instance where the routes are define.
            HttpServerMinimalExampleTest app = new HttpServerMinimalExampleTest();

            final CompletionStage<ServerBinding> binding =
                http.newServerAt("localhost", 8080)
                    .bind(app.createRoute());

            System.out.println("Server online at http://localhost:8080/\nPress RETURN to stop...");
            System.in.read(); // let it run until user presses return

            binding
                .thenCompose(ServerBinding::unbind) // trigger unbinding from the port
                .thenAccept(unbound -> system.terminate()); // and shutdown when done
            return Behaviors.empty();
        });

        // boot up server using the route as defined below
        ActorSystem.create(guardian, "routes");
    }

    private Route createRoute() {
        return concat(
            path("hello", () ->
                get(() ->
                    complete("<h1>Say hello to akka-http</h1>"))));
    }
}
//#minimal-routing-example
