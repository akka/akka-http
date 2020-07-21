/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

//#stream-random-numbers
import akka.NotUsed;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Behaviors;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;
import akka.util.ByteString;

import java.util.Random;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public class HttpServerStreamRandomNumbersTest extends AllDirectives {

  public static void main(String[] args) throws Exception {
    // boot up server using the route as defined below
    ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "routes");

    final Http http = Http.get(system);

    //In order to access all directives we need an instance where the routes are define.
    HttpServerStreamRandomNumbersTest app = new HttpServerStreamRandomNumbersTest();

    final CompletionStage<ServerBinding> binding =
        http.newServerAt("localhost", 8080)
                .bind(app.createRoute());

    System.out.println("Server online at http://localhost:8080/\nPress RETURN to stop...");
    System.in.read(); // let it run until user presses return

    binding
        .thenCompose(ServerBinding::unbind) // trigger unbinding from the port
        .thenAccept(unbound -> system.terminate()); // and shutdown when done
  }


  private Route createRoute() {
    final Random rnd = new Random();
    // streams are re-usable so we can define it here
    // and use it for every request
    Source<Integer, NotUsed> numbers = Source.fromIterator(() -> Stream.generate(rnd::nextInt).iterator());

    return concat(
        path("random", () ->
            get(() ->
                complete(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8,
                    // transform each number to a chunk of bytes
                    numbers.map(x -> ByteString.fromString(x + "\n")))))));
  }
}
//#stream-random-numbers
