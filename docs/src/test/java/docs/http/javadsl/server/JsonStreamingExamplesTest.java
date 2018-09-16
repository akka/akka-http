/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.NotUsed;
import akka.http.javadsl.common.CsvEntityStreamingSupport;
import akka.http.javadsl.common.JsonEntityStreamingSupport;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.marshalling.Marshaller;
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.Accept;
import akka.http.javadsl.server.*;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.http.javadsl.unmarshalling.StringUnmarshallers;
import akka.http.javadsl.common.EntityStreamingSupport;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import org.junit.Test;

import java.util.concurrent.CompletionStage;

//#response-streaming
import static akka.http.javadsl.server.Directives.completeOKWithSource;
import static akka.http.javadsl.server.Directives.get;
import static akka.http.javadsl.server.Directives.parameter;
import static akka.http.javadsl.server.Directives.path;

//#response-streaming
//#incoming-request-streaming
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.entityAsSourceOf;
import static akka.http.javadsl.server.Directives.extractMaterializer;
import static akka.http.javadsl.server.Directives.onComplete;
import static akka.http.javadsl.server.Directives.post;

//#incoming-request-streaming
//#csv-example
import static akka.http.javadsl.server.Directives.get;
import static akka.http.javadsl.server.Directives.path;
import static akka.http.javadsl.server.Directives.completeWithSource;

//#csv-example
public class JsonStreamingExamplesTest extends JUnitRouteTest {

  //#routes
  final Route tweets() {
    //#response-streaming

    // Step 1: Enable JSON streaming
    // we're not using this in the example, but it's the simplest way to start:
    // The default rendering is a JSON array: `[el, el, el , ...]`
    final JsonEntityStreamingSupport jsonStreaming = EntityStreamingSupport.json();

    // Step 1.1: Enable and customise how we'll render the JSON, as a compact array:
    final ByteString start = ByteString.fromString("[");
    final ByteString between = ByteString.fromString(",");
    final ByteString end = ByteString.fromString("]");
    final Flow<ByteString, ByteString, NotUsed> compactArrayRendering =
      Flow.of(ByteString.class).intersperse(start, between, end);

    final JsonEntityStreamingSupport compactJsonSupport = EntityStreamingSupport.json()
      .withFramingRendererFlow(compactArrayRendering);


    // Step 2: implement the route
    final Route responseStreaming = path("tweets", () ->
      get(() ->
        parameter(StringUnmarshallers.INTEGER, "n", n -> {
          final Source<JavaTweet, NotUsed> tws =
            Source.repeat(new JavaTweet(12, "Hello World!")).take(n);

          // Step 3: call complete* with your source, marshaller, and stream rendering mode
          return completeOKWithSource(tws, Jackson.marshaller(), compactJsonSupport);
        })
      )
    );
    //#response-streaming
    return responseStreaming;
  }

  final Route measurements() {
    //#measurement-format
    final Unmarshaller<ByteString, Measurement> Measurements = Jackson.byteStringUnmarshaller(Measurement.class);
    //#measurement-format

    //#incoming-request-streaming
    final Route incomingStreaming = path("metrics", () ->
      post(() ->
        extractMaterializer(mat -> {
            final JsonEntityStreamingSupport jsonSupport = EntityStreamingSupport.json();

            return entityAsSourceOf(Measurements, jsonSupport, sourceOfMeasurements -> {
              final CompletionStage<Integer> measurementCount = sourceOfMeasurements.runFold(0, (acc, measurement) -> acc + 1, mat);
              return onComplete(measurementCount, c -> complete("Total number of measurements: " + c));
            });
          }
        )
      )
    );
    //#incoming-request-streaming

    return incomingStreaming;
  }

  final Route csvTweets() {
    //#csv-example
    final Marshaller<JavaTweet, ByteString> renderAsCsv =
      Marshaller.withFixedContentType(ContentTypes.TEXT_CSV_UTF8, t ->
        ByteString.fromString(t.getId() + "," + t.getMessage())
      );

    final CsvEntityStreamingSupport compactJsonSupport = EntityStreamingSupport.csv();

    final Route responseStreaming = path("tweets", () ->
      get(() ->
        parameter(StringUnmarshallers.INTEGER, "n", n -> {
          final Source<JavaTweet, NotUsed> tws =
            Source.repeat(new JavaTweet(12, "Hello World!")).take(n);
          return completeWithSource(tws, renderAsCsv, compactJsonSupport);
        })
      )
    );
    //#csv-example

    return responseStreaming;
  }
  //#routes

  final void clientStreamingJsonExample() {
    //#json-streaming-client-example-raw
    Unmarshaller<ByteString, JavaTweet> unmarshal = Jackson.byteStringUnmarshaller(JavaTweet.class);
    JsonEntityStreamingSupport support = EntityStreamingSupport.json();

    // imagine receiving such response from a service:
    String payload = "{\"uid\":1,\"txt\":\"#Akka rocks!\"}\n" +
        "{\"uid\":2,\"txt\":\"Streaming is so hot right now!\"}\n" +
        "{\"uid\":3,\"txt\":\"You cannot enter the same river twice.\"}";
    HttpEntity.Strict entity = HttpEntities.create(ContentTypes.APPLICATION_JSON, payload);
    HttpResponse response = HttpResponse.create().withEntity(entity);

    Source<JavaTweet, Object> tweets =
        response.entity().getDataBytes()
        .via(support.framingDecoder()) // apply JSON framing
        .mapAsync(1, // unmarshal each element
            bs -> unmarshal.unmarshal(bs, materializer())
        );

    //#json-streaming-client-example-raw
  }

  @Test
  public void getTweetsTest() {
    //#response-streaming
    // tests:
    final TestRoute routes = testRoute(tweets());

    // test happy path
    final Accept acceptApplication = Accept.create(MediaRanges.create(MediaTypes.APPLICATION_JSON));
    routes.run(HttpRequest.GET("/tweets?n=2").addHeader(acceptApplication))
      .assertStatusCode(200)
      .assertEntity("[{\"id\":12,\"message\":\"Hello World!\"},{\"id\":12,\"message\":\"Hello World!\"}]");

    // test responses to potential errors
    final Accept acceptText = Accept.create(MediaRanges.ALL_TEXT);
    routes.run(HttpRequest.GET("/tweets?n=3").addHeader(acceptText))
      .assertStatusCode(StatusCodes.NOT_ACCEPTABLE) // 406
      .assertEntity("Resource representation is only available with these types:\napplication/json");
    //#response-streaming
  }

  @Test
  public void csvExampleTweetsTest() {
    //#response-streaming
    // tests --------------------------------------------
    final TestRoute routes = testRoute(csvTweets());

    // test happy path
    final Accept acceptCsv = Accept.create(MediaRanges.create(MediaTypes.TEXT_CSV));
    routes.run(HttpRequest.GET("/tweets?n=2").addHeader(acceptCsv))
      .assertStatusCode(200)
      .assertEntity("12,Hello World!\n" +
        "12,Hello World!\n");

    // test responses to potential errors
    final Accept acceptText = Accept.create(MediaRanges.ALL_APPLICATION);
    routes.run(HttpRequest.GET("/tweets?n=3").addHeader(acceptText))
      .assertStatusCode(StatusCodes.NOT_ACCEPTABLE) // 406
      .assertEntity("Resource representation is only available with these types:\ntext/csv; charset=UTF-8");
    //#response-streaming
  }

  //#tweet-model
  private static final class JavaTweet {
    private int id;
    private String message;

    public JavaTweet(int id, String message) {
      this.id = id;
      this.message = message;
    }

    public int getId() {
      return id;
    }

    public void setId(int id) {
      this.id = id;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    public String getMessage() {
      return message;
    }
  }
  //#tweet-model

  //#measurement-model
  private static final class Measurement {
    private String id;
    private int value;

    public Measurement(String id, int value) {
      this.id = id;
      this.value = value;
    }

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public void setValue(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }
  
  //#measurement-model
}
