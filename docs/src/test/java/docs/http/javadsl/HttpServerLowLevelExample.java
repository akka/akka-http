/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

//#low-level-server-example
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Behaviors;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.stream.SystemMaterializer;
import akka.util.ByteString;

import java.util.concurrent.CompletionStage;

public class HttpServerLowLevelExample {

  public static void main(String[] args) throws Exception {
    ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "lowlevel");

    try {
      CompletionStage<ServerBinding> serverBindingFuture =
        Http.get(system).newServerAt("localhost", 8080).bindSync(
          request -> {
            if (request.getUri().path().equals("/"))
              return HttpResponse.create().withEntity(ContentTypes.TEXT_HTML_UTF8,
                ByteString.fromString("<html><body>Hello world!</body></html>"));
            else if (request.getUri().path().equals("/ping"))
              return HttpResponse.create().withEntity(ByteString.fromString("PONG!"));
            else if (request.getUri().path().equals("/crash"))
              throw new RuntimeException("BOOM!");
            else {
              request.discardEntityBytes(system);
              return HttpResponse.create().withStatus(StatusCodes.NOT_FOUND).withEntity("Unknown resource!");
            }
          });

      System.out.println("Server online at http://localhost:8080/\nPress RETURN to stop...");
      System.in.read(); // let it run until user presses return

      serverBindingFuture
        .thenCompose(ServerBinding::unbind) // trigger unbinding from the port
        .thenAccept(unbound -> system.terminate()); // and shutdown when done

    } catch (RuntimeException e) {
      system.terminate();
    }
  }
}
//#low-level-server-example
