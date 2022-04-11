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
import akka.http.javadsl.unmarshalling.Unmarshaller;

import static akka.http.javadsl.server.Directives.entity;

public class MarshallingDirectivesTest extends JUnitRouteTest {

  @Test
  public void testEntityAsString() {
    TestRoute route =
      testRoute(
        entity(Unmarshaller.entityToString(), Directives::complete)
      );

    HttpRequest request =
      HttpRequest.POST("/")
        .withEntity("abcdef");
    route.run(request)
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("abcdef");
  }
}
