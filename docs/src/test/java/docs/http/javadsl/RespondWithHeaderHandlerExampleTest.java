/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

//#respond-with-header-exceptionhandler-example
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.ExceptionHandler;
import akka.http.javadsl.server.Route;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;

import java.io.IOException;
import java.util.concurrent.CompletionStage;
//#respond-with-header-exceptionhandler-example

//#no-exception-details-in-response
import static akka.http.javadsl.server.Directives.get;

import akka.http.scaladsl.model.IllegalHeaderException;
import akka.http.scaladsl.model.ErrorInfo;

import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import static junit.framework.TestCase.assertTrue;
//#no-exception-details-in-response

import org.junit.Test;

public class RespondWithHeaderHandlerExampleTest extends JUnitRouteTest {

    @Test
    public void compileOnlySpec() throws Exception {
        //#no-exception-details-in-response
        TestRoute route = testRoute(
          get(() -> {
              throw new IllegalHeaderException(new ErrorInfo(
                "Value of header Foo was illegal",
                "Found illegal value \"<script>alert('evil_xss_or_xsrf_reflection')</script>\""));
          })
        );

        String response = route
          .run(HttpRequest.GET("/"))
          .entityString();
        assertTrue(response.contains("header Foo was illegal"));
        assertTrue(!response.contains("evil_xss_or_xsrf_reflection"));
        //#no-exception-details-in-response
    }

    // The the other examples are only compiled, not tested:
    static
    //#respond-with-header-exceptionhandler-example
    class RespondWithHeaderHandlerExample extends AllDirectives {
        public static void main(String[] args) throws IOException {
            final ActorSystem system = ActorSystem.create();
            final ActorMaterializer materializer = ActorMaterializer.create(system);
            final Http http = Http.get(system);

            final RespondWithHeaderHandlerExample app = new RespondWithHeaderHandlerExample();

            final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = app.createRoute().flow(system, materializer);
            final CompletionStage<ServerBinding> binding = http.bindAndHandle(routeFlow, ConnectHttp.toHost("localhost", 8080), materializer);
        }

        public Route createRoute() {
            final ExceptionHandler divByZeroHandler = ExceptionHandler.newBuilder()
                    .match(ArithmeticException.class, x ->
                            complete(StatusCodes.BAD_REQUEST, "Error! You tried to divide with zero!"))
                    .build();

            return respondWithHeader(RawHeader.create("X-Outer-Header", "outer"), () -> //will apply for handled exceptions
                    handleExceptions(divByZeroHandler, () -> concat(
                            path("greetings", () -> complete("Hello!")),
                            path("divide", () -> complete("Dividing with zero: " + (1 / 0))),
                            respondWithHeader(RawHeader.create("X-Inner-Header", "inner"), () -> {
                                // Will cause Internal server error,
                                // only ArithmeticExceptions are handled by divByZeroHandler.
                                throw new RuntimeException("Boom!");                                                                     
                            })
                    ))
            );
        }
    }
//#respond-with-header-exceptionhandler-example

}
