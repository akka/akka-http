/*
 * Copyright (C) 2015-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.event.LoggingAdapter;
import akka.event.NoLogging;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.settings.ParserSettings;
import akka.http.javadsl.settings.ServerSettings;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.stream.javadsl.Flow;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static akka.http.javadsl.model.HttpProtocols.HTTP_1_1;
import static akka.http.javadsl.model.RequestEntityAcceptances.Expected;

//#customHttpMethod
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.extractMethod;

//#customHttpMethod
public class CustomHttpMethodExamplesTest extends JUnitRouteTest {

  @Test
  public void testComposition() throws InterruptedException, ExecutionException, TimeoutException {
    ActorSystem  system = system();
    LoggingAdapter loggingAdapter = NoLogging.getInstance();

    int    port = 9090;
    String host = "127.0.0.1";

    //#customHttpMethod

    // define custom method type:
    HttpMethod BOLT =
      HttpMethods.custom("BOLT", false, true, Expected);

    // add custom method to parser settings:
    final ParserSettings parserSettings =
      ParserSettings.forServer(system).withCustomMethods(BOLT);
    final ServerSettings serverSettings =
      ServerSettings.create(system).withParserSettings(parserSettings);

    final Route routes = concat(
      extractMethod( method ->
        complete( "This is a " + method.name() + " request.")
      )
    );
    final Http http = Http.get(system);
    final CompletionStage<ServerBinding> binding =
      http.newServerAt(host, port)
          .withSettings(serverSettings)
          .logTo(loggingAdapter)
          .bind(routes);

    HttpRequest request = HttpRequest.create()
      .withUri("http://" + host + ":" + Integer.toString(port))
      .withMethod(BOLT)
      .withProtocol(HTTP_1_1);

    CompletionStage<HttpResponse> response = http.singleRequest(request);
    //#customHttpMethod

    assertEquals(StatusCodes.OK, response.toCompletableFuture().get(3, TimeUnit.SECONDS).status());
    assertEquals(
      "This is a BOLT request.",
      response.toCompletableFuture().get().entity().toStrict(3000, system).toCompletableFuture().get().getData().utf8String()
    );
  }
}
