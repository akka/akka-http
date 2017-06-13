/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.ScalaTestJunitRouteTest;
import akka.http.javadsl.testkit.TestRoute;

import org.junit.Test;
import akka.http.javadsl.unmarshalling.Unmarshaller;

public class MarshallingDirectivesTest extends ScalaTestJunitRouteTest {

  @Test
  public void testEntityAsString() {
    TestRoute route =
      testRoute(
        entity(Unmarshaller.entityToString(), this::complete)
      );

    HttpRequest request =
      HttpRequest.POST("/")
        .withEntity("abcdef");
    route.run(request)
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("abcdef");
  }
}
