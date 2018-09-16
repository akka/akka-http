/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HttpServerDynamicRoutingExampleTest extends AllDirectives {

  public static void main(String[] args) throws Exception {
    // boot up server using the route as defined below
    ActorSystem system = ActorSystem.create("routes");

    final Http http = Http.get(system);
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    //In order to access all directives we need an instance where the routes are define.
    HttpServerDynamicRoutingExampleTest app = new HttpServerDynamicRoutingExampleTest();

    final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = app.createRoute().flow(system, materializer);

    http.bindAndHandle(routeFlow, ConnectHttp.toHost("localhost", 8080), materializer);
  }

  //#dynamic-routing-example
  final private Map<String, Map<JsonNode, JsonNode>> state = new ConcurrentHashMap<>();

  private Route createRoute() {
    // fixed route to update state
    Route fixedRoute = post(() ->
      pathSingleSlash(() ->
        entity(Jackson.unmarshaller(MockDefinition.class), mock -> {
          Map<JsonNode, JsonNode> mappings = new HashMap<>();
          int size = Math.min(mock.getRequests().size(), mock.getResponses().size());
          for (int i = 0; i < size; i++) {
            mappings.put(mock.getRequests().get(i), mock.getResponses().get(i));
          }
          state.put(mock.getPath(), mappings);
          return complete("ok");
        })
      )
    );

    // dynamic routing based on current state
    Route dynamicRoute = post(() ->
      state.entrySet().stream().map(mock ->
        path(mock.getKey(), () ->
          entity(Jackson.unmarshaller(JsonNode.class), input ->
            complete(StatusCodes.OK, mock.getValue().get(input), Jackson.marshaller())
          )
        )
      ).reduce(reject(), Route::orElse)
    );

    return concat(fixedRoute, dynamicRoute);
  }

  private static class MockDefinition {
    private final String path;
    private final List<JsonNode> requests;
    private final List<JsonNode> responses;

    public MockDefinition(@JsonProperty("path") String path,
                          @JsonProperty("requests") List<JsonNode> requests,
                          @JsonProperty("responses") List<JsonNode> responses) {
      this.path = path;
      this.requests = requests;
      this.responses = responses;
    }

    public String getPath() {
      return path;
    }

    public List<JsonNode> getRequests() {
      return requests;
    }

    public List<JsonNode> getResponses() {
      return responses;
    }
  }
  //#dynamic-routing-example
}
