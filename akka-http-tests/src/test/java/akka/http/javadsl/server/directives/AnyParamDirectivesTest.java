/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

import akka.http.javadsl.model.HttpCharsets;
import akka.http.javadsl.model.MediaTypes;
import akka.japi.Pair;
import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.unmarshalling.StringUnmarshallers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;

public class AnyParamDirectivesTest extends JUnitRouteTest {

  @Test
  public void testStringAnyParamExtraction() {
    TestRoute route = testRoute(anyParam("stringParam", value -> complete(value)));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(404)
      .assertEntity("Request is missing required query parameter or form field 'stringParam'");

    route
      .run(HttpRequest.create().withUri("/abc?stringParam=john"))
      .assertStatusCode(200)
      .assertEntity("john");

    route
        .run(HttpRequest.create().withUri("/abc?stringParam=a%b"))
        .assertStatusCode(400)
        .assertEntity("The request content was malformed:\nThe request's query string is invalid: stringParam=a%b");

    route
      .run(HttpRequest.create().withUri("/abc?stringParam=a=b"))
      .assertStatusCode(200)
      .assertEntity("a=b");
  }

  @Test
  public void testByteAnyParamExtraction() {
    TestRoute route = testRoute(anyParam(StringUnmarshallers.BYTE, "byteParam", value -> complete(value.toString())));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(404)
      .assertEntity("Request is missing required query parameter or form field 'byteParam'");

    route
      .run(HttpRequest.create().withUri("/abc?byteParam=test"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'byteParam' was malformed:\n'test' is not a valid 8-bit signed integer value");

    route
      .run(HttpRequest.create().withUri("/abc?byteParam=1000"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'byteParam' was malformed:\n'1000' is not a valid 8-bit signed integer value");

    route
      .run(HttpRequest.create().withUri("/abc?byteParam=48"))
      .assertStatusCode(200)
      .assertEntity("48");
  }

  @Test
  public void testShortAnyParamExtraction() {
    TestRoute route = testRoute(anyParam(StringUnmarshallers.SHORT, "shortParam", value -> complete(value.toString())));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(404)
      .assertEntity("Request is missing required query parameter or form field 'shortParam'");

    route
      .run(HttpRequest.create().withUri("/abc?shortParam=test"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'shortParam' was malformed:\n'test' is not a valid 16-bit signed integer value");

    route
      .run(HttpRequest.create().withUri("/abc?shortParam=100000"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'shortParam' was malformed:\n'100000' is not a valid 16-bit signed integer value");

    route
      .run(HttpRequest.create().withUri("/abc?shortParam=1234"))
      .assertStatusCode(200)
      .assertEntity("1234");
  }

  @Test
  public void testIntegerAnyParamExtraction() {
    TestRoute route = testRoute(anyParam(StringUnmarshallers.INTEGER, "intParam", value -> complete(value.toString())));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(404)
      .assertEntity("Request is missing required query parameter or form field 'intParam'");

    route
      .run(HttpRequest.create().withUri("/abc?intParam=test"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'intParam' was malformed:\n'test' is not a valid 32-bit signed integer value");

    route
      .run(HttpRequest.create().withUri("/abc?intParam=48"))
      .assertStatusCode(200)
      .assertEntity("48");
  }

  @Test
  public void testLongAnyParamExtraction() {
    TestRoute route = testRoute(anyParam(StringUnmarshallers.LONG, "longParam", value -> complete(value.toString())));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(404)
      .assertEntity("Request is missing required query parameter or form field 'longParam'");

    route
      .run(HttpRequest.create().withUri("/abc?longParam=test"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'longParam' was malformed:\n'test' is not a valid 64-bit signed integer value");

    route
      .run(HttpRequest.create().withUri("/abc?longParam=123456"))
      .assertStatusCode(200)
      .assertEntity("123456");
  }

  @Test
  public void testFloatAnyParamExtraction() {
    TestRoute route = testRoute(anyParam(StringUnmarshallers.FLOAT, "floatParam", value -> complete(value.toString())));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(404)
      .assertEntity("Request is missing required query parameter or form field 'floatParam'");

    route
      .run(HttpRequest.create().withUri("/abc?floatParam=test"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'floatParam' was malformed:\n'test' is not a valid 32-bit floating point value");

    route
      .run(HttpRequest.create().withUri("/abc?floatParam=48"))
      .assertStatusCode(200)
      .assertEntity("48.0");
  }

  @Test
  public void testDoubleAnyParamExtraction() {
    TestRoute route = testRoute(anyParam(StringUnmarshallers.DOUBLE, "doubleParam", value -> complete(value.toString())));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(404)
      .assertEntity("Request is missing required query parameter or form field 'doubleParam'");

    route
      .run(HttpRequest.create().withUri("/abc?doubleParam=test"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'doubleParam' was malformed:\n'test' is not a valid 64-bit floating point value");

    route
      .run(HttpRequest.create().withUri("/abc?doubleParam=48"))
      .assertStatusCode(200)
      .assertEntity("48.0");
  }

  @Test
  public void testHexBytePAnyParamExtraction() {
    TestRoute route = testRoute(anyParam(StringUnmarshallers.BYTE_HEX, "hexByteParam", value -> complete(value.toString())));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(404)
      .assertEntity("Request is missing required query parameter or form field 'hexByteParam'");

    route
      .run(HttpRequest.create().withUri("/abc?hexByteParam=test"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'hexByteParam' was malformed:\n'test' is not a valid 8-bit hexadecimal integer value");

    route
      .run(HttpRequest.create().withUri("/abc?hexByteParam=1000"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'hexByteParam' was malformed:\n'1000' is not a valid 8-bit hexadecimal integer value");

    route
      .run(HttpRequest.create().withUri("/abc?hexByteParam=48"))
      .assertStatusCode(200)
      .assertEntity(Integer.toString(0x48));
  }

  @Test
  public void testHexShortAnyParamExtraction() {
    TestRoute route = testRoute(anyParam(StringUnmarshallers.SHORT_HEX, "hexShortParam", value -> complete(value.toString())));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(404)
      .assertEntity("Request is missing required query parameter or form field 'hexShortParam'");

    route
      .run(HttpRequest.create().withUri("/abc?hexShortParam=test"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'hexShortParam' was malformed:\n'test' is not a valid 16-bit hexadecimal integer value");

    route
      .run(HttpRequest.create().withUri("/abc?hexShortParam=100000"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'hexShortParam' was malformed:\n'100000' is not a valid 16-bit hexadecimal integer value");

    route
      .run(HttpRequest.create().withUri("/abc?hexShortParam=1234"))
      .assertStatusCode(200)
      .assertEntity(Integer.toString(0x1234));
  }

  @Test
  public void testHexIntegerAnyParamExtraction() {
    TestRoute route = testRoute(anyParam(StringUnmarshallers.INTEGER_HEX, "hexIntParam", value -> complete(value.toString())));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(404)
      .assertEntity("Request is missing required query parameter or form field 'hexIntParam'");

    route
      .run(HttpRequest.create().withUri("/abc?hexIntParam=test"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'hexIntParam' was malformed:\n'test' is not a valid 32-bit hexadecimal integer value");

    route
      .run(HttpRequest.create().withUri("/abc?hexIntParam=12345678"))
      .assertStatusCode(200)
      .assertEntity(Integer.toString(0x12345678));
  }

  @Test
  public void testHexLongAnyParamExtraction() {
    TestRoute route = testRoute(anyParam(StringUnmarshallers.LONG_HEX, "hexLongParam", value -> complete(value.toString())));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(404)
      .assertEntity("Request is missing required query parameter or form field 'hexLongParam'");

    route
      .run(HttpRequest.create().withUri("/abc?hexLongParam=test"))
      .assertStatusCode(400)
      .assertEntity("The query parameter or form field 'hexLongParam' was malformed:\n'test' is not a valid 64-bit hexadecimal integer value");

    route
      .run(HttpRequest.create().withUri("/abc?hexLongParam=123456789a"))
      .assertStatusCode(200)
      .assertEntity(Long.toString(0x123456789aL));
  }

  @Test
  public void testAnyParamAsMapExtraction() {
    TestRoute route = testRoute(anyParamMap(paramMap -> {
        ArrayList<String> keys = new ArrayList<String>(paramMap.keySet());
        Collections.sort(keys);
        StringBuilder res = new StringBuilder();
        res.append(paramMap.size()).append(": [");
        for (String key : keys)
          res.append(key).append(" -> ").append(paramMap.get(key)).append(", ");
        res.append(']');
        return complete(res.toString());
      }));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(200)
      .assertEntity("0: []");

    route
      .run(HttpRequest.create().withUri("/abc?a=b"))
      .assertStatusCode(200)
      .assertEntity("1: [a -> b, ]");

    route
      .run(HttpRequest.create().withUri("/abc?a=b&c=d"))
      .assertStatusCode(200)
      .assertEntity("2: [a -> b, c -> d, ]");
  }

  @Test
  public void testAnyParamAsMultiMapExtraction() {
    TestRoute route = testRoute(anyParamMultiMap(paramMap -> {
        ArrayList<String> keys = new ArrayList<String>(paramMap.keySet());
        Collections.sort(keys);
        StringBuilder res = new StringBuilder();
        res.append(paramMap.size()).append(": [");
        for (String key : keys) {
          res.append(key).append(" -> [");
          ArrayList<String> values = new ArrayList<String>(paramMap.get(key));
          Collections.sort(values);
          for (String value : values)
            res.append(value).append(", ");
          res.append("], ");
        }
        res.append(']');
        return complete(res.toString());
      }));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(200)
      .assertEntity("0: []");

    route
      .run(HttpRequest.create().withUri("/abc?a=b"))
      .assertStatusCode(200)
      .assertEntity("1: [a -> [b, ], ]");

    route
      .run(HttpRequest.create().withUri("/abc?a=b&c=d&a=a"))
      .assertStatusCode(200)
      .assertEntity("2: [a -> [a, b, ], c -> [d, ], ]");
  }

  @Test
  public void testAnyParamAsCollectionExtraction() {
    TestRoute route = testRoute(anyParamList(paramEntries -> {
        ArrayList<Map.Entry<String, String>> entries = new ArrayList<Map.Entry<String, String>>(paramEntries);
        Collections.sort(entries, new Comparator<Map.Entry<String, String>>() {
          @Override
          public int compare(Map.Entry<String, String> e1, Map.Entry<String, String> e2) {
            int res = e1.getKey().compareTo(e2.getKey());
            return res == 0 ? e1.getValue().compareTo(e2.getValue()) : res;
          }
        });

        StringBuilder res = new StringBuilder();
        res.append(paramEntries.size()).append(": [");
        for (Map.Entry<String, String> entry : entries)
          res.append(entry.getKey()).append(" -> ").append(entry.getValue()).append(", ");
        res.append(']');
        return complete(res.toString());
      }));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(200)
      .assertEntity("0: []");

    route
      .run(HttpRequest.create().withUri("/abc?a=b&e=f&c=d"))
      .assertStatusCode(200)
      .assertEntity("3: [a -> b, c -> d, e -> f, ]");

    route
      .run(HttpRequest.create().withUri("/abc?a=b&e=f&c=d&a=z"))
      .assertStatusCode(200)
      .assertEntity("4: [a -> b, a -> z, c -> d, e -> f, ]");
  }

  @Test
  public void testOptionalIntAnyParamExtraction() {
    TestRoute route = testRoute(anyParamOptional(StringUnmarshallers.INTEGER, "optionalIntParam", value -> complete(value.toString())));

    route
      .run(HttpRequest.create().withUri("/abc"))
      .assertStatusCode(200)
      .assertEntity("Optional.empty");

    route
      .run(HttpRequest.create().withUri("/abc?optionalIntParam=23"))
      .assertStatusCode(200)
      .assertEntity("Optional[23]");
  }

  // form field related tests

  private Pair<String, String> param(String name, String value) {
    return Pair.create(name, value);
  }
  @SafeVarargs
  final private HttpRequest urlEncodedRequest(Pair<String, String>... params) {
    StringBuilder sb = new StringBuilder();
    boolean next = false;
    for (Pair<String, String> param: params) {
      if (next) {
        sb.append('&');
      }
      next = true;
      sb.append(param.first());
      sb.append('=');
      sb.append(param.second());
    }

    return HttpRequest.POST("/test")
                    .withEntity(MediaTypes.APPLICATION_X_WWW_FORM_URLENCODED.toContentType(HttpCharsets.UTF_8), sb.toString());
  }
  private HttpRequest singleParameterUrlEncodedRequest(String name, String value) {
    return urlEncodedRequest(param(name, value));
  }

  @Test
  public void testStringAnyFormFieldExtraction() {
    TestRoute route = testRoute(anyParam("stringParam", value -> complete(value)));

    route.run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter or form field 'stringParam'");

    route.run(singleParameterUrlEncodedRequest("stringParam", "john"))
            .assertStatusCode(200)
            .assertEntity("john");
  }

  @Test
  public void testByteAnyFormFieldExtraction() {
    TestRoute route = testRoute(anyParam(StringUnmarshallers.BYTE, "byteParam", value -> complete(value.toString())));

    route.run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter or form field 'byteParam'");

    route.run(singleParameterUrlEncodedRequest("byteParam", "test"))
            .assertStatusCode(400)
            .assertEntity("The query parameter or form field 'byteParam' was malformed:\n'test' is not a valid 8-bit signed integer value");

    route.run(singleParameterUrlEncodedRequest("byteParam", "1000"))
            .assertStatusCode(400)
            .assertEntity("The query parameter or form field 'byteParam' was malformed:\n'1000' is not a valid 8-bit signed integer value");

    route.run(singleParameterUrlEncodedRequest("byteParam", "48"))
            .assertStatusCode(200)
            .assertEntity("48");
  }

  @Test
  public void testOptionalIntAnyFormFieldExtraction() {
    TestRoute route = testRoute(anyParamOptional(StringUnmarshallers.INTEGER, "optionalIntParam", value -> complete(value.toString())));

    route.run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(200)
            .assertEntity("Optional.empty");

    route.run(singleParameterUrlEncodedRequest("optionalIntParam", "23"))
            .assertStatusCode(200)
            .assertEntity("Optional[23]");
  }



}
