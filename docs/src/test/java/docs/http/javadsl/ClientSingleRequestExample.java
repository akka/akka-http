/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

//#unmarshall-response-body
import akka.http.javadsl.marshallers.jackson.Jackson;

//#unmarshall-response-body

//#single-request-example
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Behaviors;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.examples.petstore.Pet;
import akka.stream.SystemMaterializer;

import java.util.concurrent.CompletionStage;

public class ClientSingleRequestExample {

  public static void main(String[] args) {
    final ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "SingleRequest");

    final CompletionStage<HttpResponse> responseFuture =
      Http.get(system)
        .singleRequest(HttpRequest.create("https://akka.io"));
  }
}
//#single-request-example

class OtherRequestResponseExamples {
  public void request() {
    //#create-simple-request
    HttpRequest.create("https://akka.io");

    // with query params
    HttpRequest.create("https://akka.io?foo=bar");
    //#create-simple-request
    //#create-post-request
    HttpRequest.POST("https://userservice.example/users")
      .withEntity(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, "data"));
    //#create-post-request

    // TODO should we have an API to create an Entity via a Marshaller?
  }

  public void response() {
    ActorSystem<Void> system = null;
    HttpResponse response = null;
    //#unmarshal-response-body
    CompletionStage<Pet> pet = Jackson.unmarshaller(Pet.class).unmarshal(response.entity(), system);
    //#unmarshal-response-body
  }
}
