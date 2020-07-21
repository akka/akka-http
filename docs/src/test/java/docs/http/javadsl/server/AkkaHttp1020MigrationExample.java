/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.actor.typed.javadsl.Behaviors;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import static akka.http.javadsl.server.Directives.*;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;

@SuppressWarnings("deprecation")
public class AkkaHttp1020MigrationExample {
    public static void main(String[] args) {
        {
            //#old-binding
            // only worked with classic actor system
            akka.actor.ActorSystem system = akka.actor.ActorSystem.create("TheSystem");
            Materializer mat = ActorMaterializer.create(system);
            Route route = get(() -> complete("Hello World!"));
            Http.get(system).bindAndHandle(route.flow(system), ConnectHttp.toHost("localhost", 8080), mat);
            //#old-binding
        }

        {
            //#new-binding
            // works with classic or typed actor system
            akka.actor.typed.ActorSystem system = akka.actor.typed.ActorSystem.create(Behaviors.empty(), "TheSystem");
            // or
            // akka.actor.ActorSystem system = akka.actor.ActorSystem.create("TheSystem");

            // materializer not needed any more

            Route route = get(() -> complete("Hello World!"));
            Http.get(system).newServerAt("localhost", 8080).bind(route);
            //#new-binding
        }
    }

}
