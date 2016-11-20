/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.Optional;
import java.util.function.Function;

import static akka.http.javadsl.server.PathMatchers.integerSegment;
import static akka.http.javadsl.server.PathMatchers.segment;

public class MiscDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testWithSizeLimit() {
    //#withSizeLimitExample
    final Route route = withSizeLimit(500, () ->
      entity(Unmarshaller.entityToString(), (entity) ->
        complete("ok")
      )
    );

    Function<Integer, HttpRequest> withEntityOfSize = (sizeLimit) -> {
      char[] charArray = new char[sizeLimit];
      Arrays.fill(charArray, '0');
      return HttpRequest.POST("/").withEntity(new String(charArray));
    };

    // tests:
    testRoute(route).run(withEntityOfSize.apply(500))
      .assertStatusCode(StatusCodes.OK);

    testRoute(route).run(withEntityOfSize.apply(501))
      .assertStatusCode(StatusCodes.BAD_REQUEST);
    //#withSizeLimitExample
  }

  @Test
  public void testWithoutSizeLimit() {
    //#withoutSizeLimitExample
    final Route route = withoutSizeLimit(() ->
      entity(Unmarshaller.entityToString(), (entity) ->
        complete("ok")
      )
    );

    Function<Integer, HttpRequest> withEntityOfSize = (sizeLimit) -> {
      char[] charArray = new char[sizeLimit];
      Arrays.fill(charArray, '0');
      return HttpRequest.POST("/").withEntity(new String(charArray));
    };

    // tests:
    // will work even if you have configured akka.http.parsing.max-content-length = 500
    testRoute(route).run(withEntityOfSize.apply(501))
      .assertStatusCode(StatusCodes.OK);
    //#withoutSizeLimitExample
  }

    //#rejectEmptyResponse
    private String explainIfEven(int i) {
      if (i % 2 == 0) return "Number " + i + " is even";
      else return "";
    }

    //#rejectEmptyResponse

  @Test
  public void testRejectEmptyResponse() {
    //#rejectEmptyResponse
    //This route responds with empty responses
    final Route routeEmptyResponse = path(segment("even").slash(integerSegment()), (Integer i) ->
      complete(explainIfEven(i))
    );
    //This route rejects when response is empty
    final Route routeRejected = rejectEmptyResponse(() ->
      path(segment("even").slash(integerSegment()), (Integer i) ->
        complete(explainIfEven(i))
      ));

    //tests:
    testRoute(routeEmptyResponse).run(HttpRequest.GET("/even/2"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Number 2 is even");
    testRoute(routeEmptyResponse).run(HttpRequest.GET("/even/1"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("");

    testRoute(routeRejected).run(HttpRequest.GET("/even/2"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Number 2 is even");
    testRoute(routeRejected).run(HttpRequest.GET("/even/1"))
      .assertStatusCode(StatusCodes.NOT_FOUND);
    //#rejectEmptyResponse
  }

  @Test
  @Ignore
  public void foo() {

    final Route route = path("foo", () -> completeOK(Optional.empty(), Jackson.marshaller()));
    final Route route2 = rejectEmptyResponse(() -> path("/foo", () -> completeOK(Optional.empty(), Jackson.marshaller())));
    final Route route3 = path("foo", () -> completeOK(Optional.of(3), Jackson.marshaller()));

    testRoute(route).run(HttpRequest.GET("/foo")).assertEntity("");
    testRoute(route2).run(HttpRequest.GET("/foo")).assertStatusCode(StatusCodes.NOT_FOUND);
    testRoute(route3).run(HttpRequest.GET("/foo")).assertEntity("3");

  }

}
