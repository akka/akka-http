/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

//#unmarshall-response-body
import akka.http.javadsl.marshallers.jackson.Jackson;

//#unmarshall-response-body

//#single-request-example
import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.examples.petstore.Pet;
import akka.stream.Materializer;

import java.util.concurrent.CompletionStage;

public class ClientSingleRequestExample {

  public static void main(String[] args) {
    final ActorSystem system = ActorSystem.create();

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
    //#create-simple-request

    //#create-post-request
    HttpRequest.POST("https://userservice.example/users")
      .withEntity(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, "data"));
    //#create-post-request

    // TODO should we have an API to create an Entity via a Marshaller?
  }

  public void response() {
    Materializer materializer = null;
    HttpResponse response = null;
    //#unmarshal-response-body
    CompletionStage<Pet> pet = Jackson.unmarshaller(Pet.class).unmarshal(response.entity(), materializer);
    //#unmarshal-response-body
  }
}