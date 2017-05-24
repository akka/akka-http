/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;


import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;

import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.marshalling.Marshaller;
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.*;
import akka.http.javadsl.unmarshalling.StringUnmarshallers;
import org.junit.Test;

import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.util.ByteString;

public class MarshallerTest extends JUnitRouteTest {

  @Test
  public void testCustomToStringMarshaller() {
    final Marshaller<Integer, RequestEntity> numberAsNameMarshaller =
      Marshaller.wrapEntity((Integer param) -> {
        switch (param) {
          case 0:
            return "null";
          case 1:
            return "eins";
          case 2:
            return "zwei";
          case 3:
            return "drei";
          case 4:
            return "vier";
          case 5:
            return "fünf";
          default:
            return "wat?";
        }
      }, Marshaller.stringToEntity(), MediaTypes.TEXT_X_SPEECH);


    final Function<Integer, Route> nummerHandler = integer -> completeOK(integer, numberAsNameMarshaller);

    TestRoute route =
      testRoute(
        get(() ->
          path("nummer", () ->
            parameter(StringUnmarshallers.INTEGER, "n", nummerHandler)
          )
        )
      );

    route.run(HttpRequest.GET("/nummer?n=1"))
      .assertStatusCode(StatusCodes.OK)
      .assertMediaType(MediaTypes.TEXT_X_SPEECH)
      .assertEntity("eins");

    route.run(HttpRequest.GET("/nummer?n=6"))
      .assertStatusCode(StatusCodes.OK)
      .assertMediaType(MediaTypes.TEXT_X_SPEECH)
      .assertEntity("wat?");

    route.run(HttpRequest.GET("/nummer?n=5"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntityBytes(ByteString.fromString("fünf", "utf8"));

    route.run(
      HttpRequest.GET("/nummer?n=5")
        .addHeader(AcceptCharset.create(HttpCharsets.ISO_8859_1.toRange())))
      .assertStatusCode(StatusCodes.OK)
      .assertEntityBytes(ByteString.fromString("fünf", "ISO-8859-1"));
  }

  @Test
  public void testCustomToByteStringMarshaller() {
    final Marshaller<Integer, RequestEntity> numberAsJsonListMarshaller =
      Marshaller.wrapEntity((Integer param) -> {
        switch (param) {
          case 1:
            return ByteString.fromString("[1]");
          case 5:
            return ByteString.fromString("[1,2,3,4,5]");
          default:
            return ByteString.fromString("[]");
        }
      }, Marshaller.byteStringToEntity(), MediaTypes.APPLICATION_JSON);

    final Function<Integer, Route> nummerHandler = integer -> completeOK(integer, numberAsJsonListMarshaller);

    TestRoute route =
      testRoute(
        get(() ->
          path("nummer", () ->
            parameter(StringUnmarshallers.INTEGER, "n", nummerHandler)
          )
        )
      );

    route.run(HttpRequest.GET("/nummer?n=1"))
      .assertStatusCode(StatusCodes.OK)
      .assertMediaType(MediaTypes.APPLICATION_JSON)
      .assertEntity("[1]");

    route.run(HttpRequest.GET("/nummer?n=5"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("[1,2,3,4,5]");

    route.run(
      HttpRequest.GET("/nummer?n=5").addHeader(Accept.create(MediaTypes.TEXT_PLAIN.toRange())))
      .assertStatusCode(StatusCodes.NOT_ACCEPTABLE);
  }

  @Test
  public void testCustomToEntityMarshaller() {
    final Marshaller<Integer, RequestEntity> numberAsJsonListMarshaller =
      Marshaller.withFixedContentType(MediaTypes.APPLICATION_JSON.toContentType(), (Integer param) -> {
        switch (param) {
          case 1:
            return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[1]");
          case 5:
            return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[1,2,3,4,5]");
          default:
            return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[]");
        }
      });

    final Function<Integer, Route> nummerHandler = integer -> completeOK(integer, numberAsJsonListMarshaller);

    TestRoute route =
      testRoute(
        get(() ->
          path("nummer", () ->
            parameter(StringUnmarshallers.INTEGER, "n", nummerHandler)
          )
        ).seal(system(), materializer()) // needed to get the content negotiation, maybe
      );

    route.run(HttpRequest.GET("/nummer?n=1"))
      .assertStatusCode(StatusCodes.OK)
      .assertMediaType(MediaTypes.APPLICATION_JSON)
      .assertEntity("[1]");

    route.run(HttpRequest.GET("/nummer?n=5"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("[1,2,3,4,5]");

    route.run(
      HttpRequest.GET("/nummer?n=5").addHeader(Accept.create(MediaTypes.TEXT_PLAIN.toRange())))
        .assertStatusCode(StatusCodes.NOT_ACCEPTABLE);
  }

  @Test
  public void testCustomToResponseMarshaller() {
    final Marshaller<Integer, HttpResponse> numberAsJsonListMarshaller =
      Marshaller.withFixedContentType(MediaTypes.APPLICATION_JSON.toContentType(), (Integer param) -> {
        switch (param) {
          case 1:
            return HttpResponse.create().withEntity(MediaTypes.APPLICATION_JSON.toContentType(), "[1]");
          case 5:
            return HttpResponse.create().withEntity(MediaTypes.APPLICATION_JSON.toContentType(), "[1,2,3,4,5]");
          default:
            return HttpResponse.create().withStatus(StatusCodes.NOT_FOUND);
        }
      });

    final Function<Integer, Route> nummerHandler = integer -> complete(integer, numberAsJsonListMarshaller);

    TestRoute route =
      testRoute(
        get(() ->
          path("nummer", () ->
            parameter(StringUnmarshallers.INTEGER, "n", nummerHandler)
          )
        )
      );

    route.run(HttpRequest.GET("/nummer?n=1"))
      .assertStatusCode(StatusCodes.OK)
      .assertMediaType(MediaTypes.APPLICATION_JSON)
      .assertEntity("[1]");

    route.run(HttpRequest.GET("/nummer?n=5"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("[1,2,3,4,5]");

    route.run(HttpRequest.GET("/nummer?n=6"))
      .assertStatusCode(StatusCodes.NOT_FOUND);

    route.run(HttpRequest.GET("/nummer?n=5").addHeader(Accept.create(MediaTypes.TEXT_PLAIN.toRange())))
      .assertStatusCode(StatusCodes.NOT_ACCEPTABLE);
  }

  @Test
  public void testExistingOptionalEntities() {
    optionalTest(Optional.of(42), "42");
    optionalTest(OptionalDouble.of(42d), "42.0");
    optionalTest(OptionalInt.of(42), "42");
    optionalTest(OptionalLong.of(42L), "42");
  }

  @Test
  public void testRejectAbsentEntities() {
    optionalTest(Optional.empty());
    optionalTest(OptionalDouble.empty());
    optionalTest(OptionalInt.empty());
    optionalTest(OptionalLong.empty());
  }

  @Test
  public void tesIfAbsentOptionalsAreSkipped() {
    final Route route = path("foo", () -> completeOK(new DummyClass(Optional.empty(), "test"), Jackson.marshaller()));

    testRoute(route).run(HttpRequest.GET("/foo")).assertEntity("{\"someString\":\"test\"}");
  }

  @Test
  public void tesIfStringsAreHandledWell() {
    final Route nullStringRoute = path("foo", () -> completeOK("null", Jackson.marshaller()));
    final Route sentenceRoute = path("foo", () -> completeOK("Some sentence.", Jackson.marshaller()));
    final Route nullRoute = path("foo", () -> completeOK(null, Jackson.marshaller()));
    final Route trickyRoute = path("foo", () -> completeOK("\"nu\"ll\"", Jackson.marshaller()));
    final Route quotedRoute = path("foo", () -> completeOK("\"null\"", Jackson.marshaller()));

    testRoute(nullStringRoute).run(HttpRequest.GET("/foo")).assertEntity("null");
    testRoute(sentenceRoute).run(HttpRequest.GET("/foo")).assertEntity("Some sentence.");
    testRoute(nullRoute).run(HttpRequest.GET("/foo")).assertEntity("");
    testRoute(quotedRoute).run(HttpRequest.GET("/foo")).assertEntity("\\\"null\\\"");
    testRoute(trickyRoute).run(HttpRequest.GET("/foo")).assertEntity("\\\"nu\\\"ll\\\"");
  }

  private <T> void optionalTest(T absentOptional) {
    final Route absentOptionalRoute = path("foo", () -> completeOK(absentOptional, Jackson.marshaller()));
    final Route rejectAbsentOptionalRoute = rejectEmptyResponse(() -> path("foo", () -> completeOK(absentOptional, Jackson.marshaller())));

    testRoute(rejectAbsentOptionalRoute).run(HttpRequest.GET("/foo")).assertStatusCode(StatusCodes.NOT_FOUND);
    testRoute(absentOptionalRoute).run(HttpRequest.GET("/foo")).assertEntity("");
  }

  private <T> void optionalTest(T optional, String body) {
    final Route route3 = path("foo", () -> completeOK(optional, Jackson.marshaller()));

    testRoute(route3).run(HttpRequest.GET("/foo")).assertEntity(body);
  }

  static private class DummyClass {
    private final Optional<Integer> optionalInt;
    private final String someString;

    DummyClass(Optional<Integer> optionalInt, String someString) {
      this.optionalInt = optionalInt;
      this.someString = someString;
    }

    public Optional<Integer> getOptionalInt() {
      return optionalInt;
    }

    public String getSomeString() {
      return someString;
    }
  }
}
