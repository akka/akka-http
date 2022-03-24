/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.AttributeKey;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;

import org.junit.Test;

public class AttributeDirectivesTest extends JUnitRouteTest {
  AttributeKey<String> key = AttributeKey.create("my-key", String.class);

  @Test
  public void testAttribute() {
    TestRoute route = testRoute(attribute(key, value -> complete("Completed with value [" + value + "]")));

    route
      .run(HttpRequest.create().addAttribute(key, "the-value"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Completed with value [the-value]");

    // A missing attribute is a programming error:
    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
  }

  @Test
  public void testOptionalHeaderValueByName() {
    TestRoute route = testRoute(optionalAttribute(key, (opt) -> complete(opt.toString())));

    route
      .run(HttpRequest.create().addAttribute(key, "the-value"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Optional[the-value]");

    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Optional.empty");
  }

}
