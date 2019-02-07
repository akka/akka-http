/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

public class RequestDirectivesTest extends JUnitRouteTest {

  @Test
  public void testMapRequest() {
    TestRoute route =
        testRoute(
            get(() ->
                mapRequest(request -> request.withStringRepresentation(rq -> "123"), () ->
                    path("abc", () ->
                        extractRequest(req ->
                            complete(req.toString())
                        )))));

    HttpRequest request =
        HttpRequest.GET("/abc");

    route.run(request)
        .assertStatusCode(StatusCodes.OK)
        .assertEntity("123");
  }

}
