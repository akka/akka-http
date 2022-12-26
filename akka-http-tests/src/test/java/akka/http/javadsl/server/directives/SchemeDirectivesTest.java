/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.server.Directives;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

import static akka.http.javadsl.server.Directives.*;

public class SchemeDirectivesTest extends JUnitRouteTest {
  @Test
  public void testSchemeFilter() {
    TestRoute route = testRoute(scheme("http", () -> complete("OK!")));

    route
      .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK!");

    route
      .run(HttpRequest.create().withUri(Uri.create("https://example.org")))
      .assertStatusCode(StatusCodes.BAD_REQUEST)
      .assertEntity("Uri scheme not allowed, supported schemes: http");
  }

  @Test
  public void testSchemeExtraction() {
    TestRoute route = testRoute(extractScheme(Directives::complete));

    route
      .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("http");

    route
      .run(HttpRequest.create().withUri(Uri.create("https://example.org")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("https");
  }


}
