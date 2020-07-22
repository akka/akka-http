/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.testkit.TestKit;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import scala.concurrent.duration.Duration;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class TimeoutDirectivesExamplesTest extends AllDirectives {
    //#testSetup
    private final Config testConf = ConfigFactory.parseString("akka.loggers = [\"akka.testkit.TestEventListener\"]\n"
            + "akka.loglevel = ERROR\n"
            + "akka.stdout-loglevel = ERROR\n"
            + "windows-connection-abort-workaround-enabled = auto\n"
            + "akka.log-dead-letters = OFF\n"
            + "akka.http.server.request-timeout = 1000s");
    // large timeout - 1000s (please note - setting to infinite will disable Timeout-Access header
    // and withRequestTimeout will not work)

    private final ActorSystem system = ActorSystem.create("TimeoutDirectivesExamplesTest", testConf);

    private final Http http = Http.get(system);

    private void shutdown(ServerBinding b) throws Exception {
        System.out.println(String.format("Unbinding from %s", b.localAddress()));

        b.unbind().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private Optional<HttpResponse> runRoute(Route route, String routePath) throws Exception {
        final ServerBinding binding = http.newServerAt("localhost", 0).bind(route).toCompletableFuture().get();

        final CompletionStage<HttpResponse> responseCompletionStage = http.singleRequest(HttpRequest.create("http://localhost:" + binding.localAddress().getPort() + "/" + routePath));

        CompletableFuture<HttpResponse> responseFuture = responseCompletionStage.toCompletableFuture();

        Optional<HttpResponse> responseOptional;
        try {
            responseOptional = Optional.of(responseFuture.get(3, TimeUnit.SECONDS)); // patienceConfig
        } catch (Exception e) {
            responseOptional = Optional.empty();
        }

        shutdown(binding);

        return responseOptional;
    }
    //#testSetup

    @After
    public void shutDown() {
        TestKit.shutdownActorSystem(system, Duration.create(1, TimeUnit.SECONDS), false);
    }

    @Test
    public void testRequestTimeoutIsConfigurable() throws Exception {
        //#withRequestTimeout-plain
        final Duration timeout = Duration.create(1, TimeUnit.SECONDS);
        CompletionStage<String> slowFuture = new CompletableFuture<>();

        final Route route = path("timeout", () ->
                withRequestTimeout(timeout, () -> {
                    return completeOKWithFutureString(slowFuture); // very slow
                })
        );

        // test:
        StatusCode statusCode = runRoute(route, "timeout").get().status();
        assert (StatusCodes.SERVICE_UNAVAILABLE.equals(statusCode));
        //#withRequestTimeout-plain
    }

    @Test
    public void testRequestWithoutTimeoutCancelsTimeout() throws Exception {
        //#withoutRequestTimeout-1
        CompletionStage<String> slowFuture = new CompletableFuture<>();

        final Route route = path("timeout", () ->
                withoutRequestTimeout(() -> {
                    return completeOKWithFutureString(slowFuture); // very slow
                })
        );

        // test:
        Boolean receivedReply = runRoute(route, "timeout").isPresent();
        assert (!receivedReply); // timed-out
        //#withoutRequestTimeout-1
    }

    @Test
    public void testRequestTimeoutAllowsCustomResponse() throws Exception {
        //#withRequestTimeout-with-handler
        final Duration timeout = Duration.create(1, TimeUnit.MILLISECONDS);
        CompletionStage<String> slowFuture = new CompletableFuture<>();

        HttpResponse enhanceYourCalmResponse = HttpResponse.create()
                .withStatus(StatusCodes.ENHANCE_YOUR_CALM)
                .withEntity("Unable to serve response within time limit, please enhance your calm.");

        final Route route = path("timeout", () ->
                withRequestTimeout(timeout, (request) -> enhanceYourCalmResponse, () -> {
                    return completeOKWithFutureString(slowFuture); // very slow
                })
        );

        // test:
        StatusCode statusCode = runRoute(route, "timeout").get().status();
        assert (StatusCodes.ENHANCE_YOUR_CALM.equals(statusCode));
        //#withRequestTimeout-with-handler
    }

    // make it compile only to avoid flaking in slow builds
    @Ignore("Compile only test")
    @Test
    public void testRequestTimeoutCustomResponseCanBeAddedSeparately() throws Exception {
        //#withRequestTimeoutResponse
        final Duration timeout = Duration.create(100, TimeUnit.MILLISECONDS);
        CompletionStage<String> slowFuture = new CompletableFuture<>();

        HttpResponse enhanceYourCalmResponse = HttpResponse.create()
                .withStatus(StatusCodes.ENHANCE_YOUR_CALM)
                .withEntity("Unable to serve response within time limit, please enhance your calm.");

        final Route route = path("timeout", () ->
                withRequestTimeout(timeout, () ->
                        // racy! for a very short timeout like 1.milli you can still get 503
                        withRequestTimeoutResponse((request) -> enhanceYourCalmResponse, () -> {
                            return completeOKWithFutureString(slowFuture); // very slow
                        }))
        );

        // test:
        StatusCode statusCode = runRoute(route, "timeout").get().status();
        assert (StatusCodes.ENHANCE_YOUR_CALM.equals(statusCode));
        //#withRequestTimeoutResponse
    }

    @Test
    public void extractRequestTimeout() throws Exception {
        //#extractRequestTimeout
        Duration timeout1 = Duration.create(500, TimeUnit.MILLISECONDS);
        Duration timeout2 = Duration.create(1000, TimeUnit.MILLISECONDS);
        Route route =
          path("timeout", () ->
            withRequestTimeout(timeout1, () ->
              extractRequestTimeout( t1 ->
                withRequestTimeout(timeout2, () ->
                  extractRequestTimeout( t2 -> {
                    if (t1 == timeout1 && t2 == timeout2)
                      return complete(StatusCodes.OK);
                    else
                      return complete(StatusCodes.INTERNAL_SERVER_ERROR);
                  })
                )
              )
            )
          );
        //#extractRequestTimeout
        StatusCode statusCode = runRoute(route, "timeout").get().status();
        assert (StatusCodes.OK.equals(statusCode));
    }
}
