/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Directives;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

import static akka.http.javadsl.common.PartialApplication.*;
import static akka.http.javadsl.server.Directives.*;

public class ComposingDirectivesTest extends JUnitRouteTest {

  @Test
  public void testAnyOf0Arg() {
    TestRoute getOrPost = testRoute(path("hello", () ->
      anyOf(Directives::get, Directives::post, () ->
        complete("hi"))));

    getOrPost
      .run(HttpRequest.GET("/hello"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("hi");

    getOrPost
      .run(HttpRequest.POST("/hello"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("hi");

    getOrPost
      .run(HttpRequest.PUT("/hello"))
      .assertStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
  }

  @Test
  public void testAnyOf1Arg() {
    TestRoute someParam = testRoute(path("param", () ->
      anyOf(bindParameter(Directives::parameter, "foo"), bindParameter(Directives::parameter, "bar"), (String param) -> complete("param is " + param)))
    );

    someParam
      .run(HttpRequest.GET("/param?foo=foz"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("param is foz");

    someParam
      .run(HttpRequest.GET("/param?bar=baz"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("param is baz");

    someParam
      .run(HttpRequest.GET("/param?charlie=alice"))
      .assertStatusCode(StatusCodes.NOT_FOUND);
  }

  @Test
  public void testAllOf0Arg() {
    TestRoute charlie = testRoute(allOf(
      bindParameter(Directives::pathPrefix, "alice"),
      bindParameter(Directives::path, "bob"),
      () -> complete("Charlie!")));

    charlie.run(HttpRequest.GET("/alice/bob"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Charlie!");

    charlie.run(HttpRequest.GET("/alice"))
      .assertStatusCode(StatusCodes.NOT_FOUND);

    charlie.run(HttpRequest.GET("/bob"))
      .assertStatusCode(StatusCodes.NOT_FOUND);
  }

  @Test
  public void testAllOf1Arg() {
    TestRoute extractTwo = testRoute(path("extractTwo", () ->
      allOf(Directives::extractScheme, Directives::extractMethod, (scheme, method) -> complete("You did a " + method.name() + " using " + scheme))
    ));

    extractTwo
      .run(HttpRequest.GET("/extractTwo"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("You did a GET using http");

    extractTwo
      .run(HttpRequest.PUT("/extractTwo"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("You did a PUT using http");
  }

  @Test
  public void testAllOf0And1Arg() {
    TestRoute route = testRoute(allOf(bindParameter(Directives::pathPrefix, "guess"), Directives::extractMethod, method -> complete("You did a " + method.name())));

    route
      .run(HttpRequest.GET("/guess"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("You did a GET");

    route
      .run(HttpRequest.POST("/guess"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("You did a POST");
  }

}
