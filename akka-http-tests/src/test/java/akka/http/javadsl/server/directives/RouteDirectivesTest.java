/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.headers.Location;
import akka.http.javadsl.server.Directives;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.stream.javadsl.Sink;
import akka.util.ByteString;
import org.junit.Test;

public class RouteDirectivesTest extends JUnitRouteTest {

  @Test
  public void testRedirection() {
    Uri targetUri = Uri.create("http://example.com");
    TestRoute route =
      testRoute(
        redirect(targetUri, StatusCodes.FOUND)
      );

    route
      .run(HttpRequest.create())
      .assertStatusCode(302)
      .assertHeaderExists(Location.create(targetUri));
  }

  @Test
  public void testEntitySizeNoLimit() {
    TestRoute route =
      testRoute(
        path("no-limit", () ->
          extractEntity(entity ->
            extractMaterializer(mat ->
              Directives.<ByteString>onSuccess(entity // fails to infer type parameter with some older oracle JDK versions
                  .withoutSizeLimit()
                  .getDataBytes()
                  .runWith(Sink.<ByteString>head(), mat),
                bytes -> complete(bytes.utf8String())
              )
            )
          )
        )
      );

    route
      .run(HttpRequest.create("/no-limit").withEntity("1234567890"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("1234567890");
  }

  private TestRoute routeWithLimit() {
    return testRoute(
      path("limit-5", () ->
        extractEntity(entity ->
          extractMaterializer(mat ->
            Directives.<ByteString>onSuccess(entity // fails to infer type parameter with some older oracle JDK versions
                .withSizeLimit(5)
                .getDataBytes()
                .runWith(Sink.head(), mat),
              bytes -> complete(bytes.utf8String())
            )
          )
        )
      )
    );
  }

  @Test
  public void testEntitySizeWithinLimit() {
    TestRoute route = routeWithLimit();

    route
      .run(HttpRequest.create("/limit-5").withEntity("12345"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("12345");
  }

  @Test
  public void testEntitySizeLargerThanLimit() {
    TestRoute route = routeWithLimit();

    route
      .run(HttpRequest.create("/limit-5").withEntity("1234567890"))
      .assertStatusCode(StatusCodes.PAYLOAD_TOO_LARGE)
      .assertEntity("EntityStreamSizeException: incoming entity size (10) exceeded size limit (5 bytes)! " +
              "This may have been a parser limit (set via `akka.http.[server|client].parsing.max-content-length`), " +
	      "a decoder limit (set via `akka.http.routing.decode-max-size`), " +
              "or a custom limit set with `withSizeLimit`.");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testEmptyRoutesConcatenation() {
    route();
  }
}
