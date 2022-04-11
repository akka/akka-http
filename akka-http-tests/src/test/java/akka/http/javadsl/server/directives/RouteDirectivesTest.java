/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.Location;
import akka.http.javadsl.server.Directives;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.stream.javadsl.Sink;
import akka.util.ByteString;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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

  @Test
  public void testRouteFromFunction() {
    TestRoute route = testRoute(
      handle(req ->
        // CompletableFuture.completedStage isn't available until Java 9
        CompletableFuture.supplyAsync(() -> HttpResponse.create().withEntity(HttpEntities.create(req.getUri().toString())))
      )
    );

    route.run(HttpRequest.create("/foo"))
      .assertEntity("http://example.com/foo");
  }

  @Test
  public void testRouteFromFailingFunction() {
    TestRoute route = testRoute(
      handle(req ->
        // CompletableFuture.failedStage/failedFuture aren't available until Java 9
        CompletableFuture.supplyAsync(() -> { throw new IllegalStateException("x"); })
      ),
      complete(StatusCodes.IM_A_TEAPOT)
    );

    route.run(HttpRequest.create("/foo"))
      .assertEntity("There was an internal server error.");
  }

  @Test
  public void testRouteWhenLambdaThrows() {
    TestRoute route = testRoute(
      handle(req -> { throw new IllegalStateException("x"); }),
      complete(StatusCodes.IM_A_TEAPOT)
    );

    route.run(HttpRequest.create("/foo"))
      .assertEntity("There was an internal server error.");
  }
}
