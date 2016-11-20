/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.impl.model.parser.AcceptLanguageHeader;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.*;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import akka.http.javadsl.testkit.JUnitRouteTest;
import jdk.nashorn.internal.runtime.FunctionInitializer;
import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static akka.http.javadsl.server.PathMatchers.*;


public class MiscDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testWithSizeLimit() {
    //#withSizeLimitExample
    final Route route = withSizeLimit(500, () ->
      entity(Unmarshaller.entityToString(), (entity) ->
        complete("ok")
      )
    );

    Function<Integer, HttpRequest> withEntityOfSize = (sizeLimit) -> {
      char[] charArray = new char[sizeLimit];
      Arrays.fill(charArray, '0');
      return HttpRequest.POST("/").withEntity(new String(charArray));
    };

    // tests:
    testRoute(route).run(withEntityOfSize.apply(500))
      .assertStatusCode(StatusCodes.OK);

    testRoute(route).run(withEntityOfSize.apply(501))
      .assertStatusCode(StatusCodes.BAD_REQUEST);
    //#withSizeLimitExample
  }

  @Test
  public void testWithoutSizeLimit() {
    //#withoutSizeLimitExample
    final Route route = withoutSizeLimit(() ->
      entity(Unmarshaller.entityToString(), (entity) ->
        complete("ok")
      )
    );

    Function<Integer, HttpRequest> withEntityOfSize = (sizeLimit) -> {
      char[] charArray = new char[sizeLimit];
      Arrays.fill(charArray, '0');
      return HttpRequest.POST("/").withEntity(new String(charArray));
    };

    // tests:
    // will work even if you have configured akka.http.parsing.max-content-length = 500
    testRoute(route).run(withEntityOfSize.apply(501))
      .assertStatusCode(StatusCodes.OK);
    //#withoutSizeLimitExample
  }

  @Test
  public void testExtractClientIP() throws UnknownHostException {
    //#extractClientIPExample
    final Route route = extractClientIP(remoteAddr ->
      complete("Client's IP is " + remoteAddr.getAddress().map(InetAddress::getHostAddress)
        .orElseGet(() -> "unknown"))
    );

    final String ip = "192.168.1.2";
    testRoute(route).run(HttpRequest.GET("/")
        .addHeader(RemoteAddress
          .create(akka.http.javadsl.model.RemoteAddress.create(InetAddress.getByName(ip)))))
      .assertEntity("Client's IP is " + ip);

    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("Client's IP is unknown");
    //#extractClientIPExample
  }

  @Test
  public void testRequestEntityEmpty() {
    //#requestEntity-empty-present-example
    final Route route = requestEntityEmpty(() ->
      complete("request entity empty")
    ).orElse(requestEntityPresent(() ->
      complete("request entity present")
    ));

    testRoute(route).run(HttpRequest.POST("/"))
      .assertEntity("request entity empty");
    testRoute(route).run(HttpRequest.POST("/").withEntity("foo"))
      .assertEntity("request entity present");
    //#requestEntity-empty-present-example
  }

  @Test
  public void testSelectPreferredLanguage() {
    //#selectPreferredLanguage
    final Route enRoute = selectPreferredLanguage(
      Arrays.asList(Language.create("en"), Language.create("en-US")), lang ->
        complete(lang.toString())
    );
    final Route deHuRoute = selectPreferredLanguage(
      Arrays.asList(Language.create("de-DE"), Language.create("hu")), lang ->
        complete(lang.toString())
    );

    final HttpRequest request = HttpRequest.GET("/").addHeader(AcceptLanguage.create(
      Language.create("en-US").withQValue(1f),
      Language.create("en").withQValue(0.7f),
      LanguageRanges.ALL.withQValue(0.1f),
      Language.create("de-DE").withQValue(0.5f)
    ));

    testRoute(enRoute).run(request).assertEntity("en-US");
    testRoute(deHuRoute).run(request).assertEntity("de-DE");
    //#selectPreferredLanguage
  }

}
