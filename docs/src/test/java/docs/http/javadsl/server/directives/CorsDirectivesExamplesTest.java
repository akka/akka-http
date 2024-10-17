/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpMethods;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.*;
import akka.http.javadsl.server.Rejections;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.model.StatusCodes;
import org.junit.Test;

public class CorsDirectivesExamplesTest extends JUnitRouteTest {

    @Test
    public void cors() {
        //#cors
        final Route route = cors(() ->
                complete(StatusCodes.OK)
        );

        // tests:
        // preflight
        HttpOrigin exampleOrigin = HttpOrigin.parse("http://example.com");
        testRoute(route).run(HttpRequest.OPTIONS("/")
                        .addHeader(Origin.create(exampleOrigin))
                        .addHeader(AccessControlRequestMethod.create(HttpMethods.GET)))
                .assertStatusCode(StatusCodes.OK)
                .assertHeaderExists(AccessControlAllowOrigin.create(HttpOriginRange.create(exampleOrigin)))
                .assertHeaderExists(AccessControlAllowMethods.create(HttpMethods.GET, HttpMethods.POST, HttpMethods.HEAD, HttpMethods.OPTIONS))
                .assertHeaderExists(AccessControlMaxAge.create(1800))
                .assertHeaderExists(AccessControlAllowCredentials.create(true));

        // regular call
        runRouteUnSealed(route, HttpRequest.GET("/")
                .addHeader(Origin.create(exampleOrigin)))
                .assertStatusCode(StatusCodes.OK)
                .assertHeaderExists(AccessControlAllowOrigin.create(HttpOriginRange.create(exampleOrigin)))
                .assertHeaderExists(AccessControlAllowCredentials.create(true));
        //#cors
    }

}
