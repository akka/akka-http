/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.marshalling.Marshaller;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.scaladsl.model.StatusCodes;
import akka.japi.pf.PFBuilder;
import akka.pattern.CircuitBreaker;
import akka.testkit.javadsl.TestKit;
import org.junit.Ignore;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import static akka.http.javadsl.server.PathMatchers.*;

//#onComplete
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.onComplete;
import static akka.http.javadsl.server.Directives.path;

//#onComplete
//#onSuccess
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.onSuccess;
import static akka.http.javadsl.server.Directives.path;

//#onSuccess
//#completeOrRecoverWith
import static akka.http.javadsl.server.Directives.completeOrRecoverWith;
import static akka.http.javadsl.server.Directives.failWith;

//#completeOrRecoverWith
//#onCompleteWithBreaker
import static akka.http.javadsl.server.Directives.onCompleteWithBreaker;
import static akka.http.javadsl.server.Directives.path;

//#onCompleteWithBreaker

public class FutureDirectivesExamplesTest extends JUnitRouteTest {

    @Test
    public void testOnComplete() {
        //#onComplete
        // import static scala.compat.java8.JFunction.func;
        // import static akka.http.javadsl.server.PathMatchers.*;

        final Route route = path(segment("divide").slash(integerSegment()).slash(integerSegment()),
                (a, b) -> onComplete(
                        () -> CompletableFuture.supplyAsync(() -> a / b),
                        maybeResult -> maybeResult
                                .map(result -> complete("The result was " + result))
                                .recover(new PFBuilder<Throwable, Route>()
                                        .matchAny(ex -> complete(StatusCodes.InternalServerError(),
                                                "An error occurred: " + ex.getMessage())
                                        )
                                        .build())
                                .get()
                )
        );

        testRoute(route).run(HttpRequest.GET("/divide/10/2"))
                .assertEntity("The result was 5");

        testRoute(route).run(HttpRequest.GET("/divide/10/0"))
                .assertStatusCode(StatusCodes.InternalServerError())
                .assertEntity("An error occurred: / by zero");
        //#onComplete
    }

    @Test
    public void testOnSuccess() {
        //#onSuccess
        final Route route = path("success", () ->
                onSuccess(CompletableFuture.supplyAsync(() -> "Ok"),
                        extraction -> complete(extraction)
                )
        ).orElse(path("failure", () ->
                onSuccess(CompletableFuture.supplyAsync(() -> {
                            throw new RuntimeException();
                        }),
                        extraction -> complete("never reaches here"))
        ));

        testRoute(route).run(HttpRequest.GET("/success"))
                .assertEntity("Ok");

        testRoute(route).run(HttpRequest.GET("/failure"))
                .assertStatusCode(StatusCodes.InternalServerError())
                .assertEntity("There was an internal server error.");
        //#onSuccess
    }

    @Test
    public void testCompleteOrRecoverWith() {
        //#completeOrRecoverWith
        final Route route = path("success", () ->
                completeOrRecoverWith(
                        () -> CompletableFuture.supplyAsync(() -> "Ok"),
                        Marshaller.stringToEntity(),
                        extraction -> failWith(extraction) // not executed
                )
        ).orElse(path("failure", () ->
                completeOrRecoverWith(
                        () -> CompletableFuture.supplyAsync(() -> {
                            throw new RuntimeException();
                        }),
                        Marshaller.stringToEntity(),
                        extraction -> failWith(extraction))
        ));

        testRoute(route).run(HttpRequest.GET("/success"))
                .assertEntity("Ok");

        testRoute(route).run(HttpRequest.GET("/failure"))
                .assertStatusCode(StatusCodes.InternalServerError())
                .assertEntity("There was an internal server error.");
        //#completeOrRecoverWith
    }

    // The test has a race condition because CircuitBreakers do not guarantee certain happens-before relationships
    // between triggering and reporting errors for ongoing calls. This test fails a lot so disabling for now.
    @Ignore
    @Test
    public void testOnCompleteWithBreaker() throws InterruptedException {
        //#onCompleteWithBreaker
        // import static scala.compat.java8.JFunction.func;
        // import static akka.http.javadsl.server.PathMatchers.*;

        final int maxFailures = 1;
        final Duration callTimeout = Duration.ofSeconds(5);
        final Duration resetTimeout = Duration.ofSeconds(1);
        final CircuitBreaker breaker = CircuitBreaker.create(system().scheduler(), maxFailures, callTimeout, resetTimeout);

        final Route route = path(segment("divide").slash(integerSegment()).slash(integerSegment()),
                (a, b) -> onCompleteWithBreaker(breaker,
                        () -> CompletableFuture.supplyAsync(() -> a / b),
                        maybeResult -> maybeResult
                                .map(result -> complete("The result was " + result))
                                .recover(new PFBuilder<Throwable, Route>()
                                        .matchAny(ex -> complete(StatusCodes.InternalServerError(),
                                                "An error occurred: " + ex.toString())
                                        )
                                        .build())
                                .get()
                )
        );

        testRoute(route).run(HttpRequest.GET("/divide/10/2"))
                .assertEntity("The result was 5");

        testRoute(route).run(HttpRequest.GET("/divide/10/0"))
                .assertStatusCode(StatusCodes.InternalServerError())
                .assertEntity("An error occurred: java.lang.ArithmeticException: / by zero");

        // The circuit-breaker will eventually be opened
        new TestKit(system()) {
            {
                awaitAssert(
                        Duration.ofSeconds(500),
                        () -> {
                            testRoute(route).run(HttpRequest.GET("/divide/10/0"))
                                    .assertEntity("The server is currently unavailable (because it is overloaded or down for maintenance).")
                                    .assertStatusCode(StatusCodes.ServiceUnavailable());
                            return null;
                        });

                Thread.sleep(resetTimeout.toMillis());

                // circuit breaker resets after this time, but observing it
                // is timing sensitive so retry a few times within a timeout
                awaitAssert(
                        Duration.ofSeconds(500),
                        () -> {
                            testRoute(route).run(HttpRequest.GET("/divide/8/2"))
                                    .assertEntity("The result was 4");
                            return null;
                        });
            }
        };
        //#onCompleteWithBreaker
    }

}
