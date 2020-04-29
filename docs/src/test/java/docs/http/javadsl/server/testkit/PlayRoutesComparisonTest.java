/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.testkit;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static akka.http.javadsl.server.PathMatchers.*;
import static akka.http.javadsl.server.Directives.*;


public class PlayRoutesComparisonTest extends JUnitRouteTest {

    static class Clients {
        static String list() {
            return "clientA,clientB,clientC";
        }

        static String get(long id) {
            return "clientB";
        }
    }

    static String download(String names) {
        return "images/logo.png: file contents";
    }

    static String getPage(String page) {
        return "The requested [" + page + "].";
    }

    static String apiList(Optional<String> version) {
        return version.map(v -> "aa,bb,cc").orElse("ff");
    }

    static String apiItems(List<String> items) {
        return items.stream().collect(Collectors.joining(","));
    }

    static class Routes extends AllDirectives {

        Route clientsAll() {
            return
                    // #fixed
                    get(() ->
                            path(segment("clients").slash("all"), () ->
                                    complete(Clients.list())
                            )
                    );
            // #fixed
        }

        Route clientById() {
            return
                    // #long
                    get(() ->
                            path(segment("client").slash(longSegment()), id ->
                                    complete(Clients.get(id))
                            )
                    );
            // #long
        }

        Route files() {
            return
                    // #remaining
                    get(() ->
                            path(segment("files").slash(remaining()), names ->
                                    complete(download(names))
                            )
                    );
            // #remaining
        }

        Route pageParameter() {
            return
                    // #mandatory-parameter
                    get(() ->
                            parameter("page", page ->
                                    complete(getPage(page))
                            )
                    );
            // #mandatory-parameter
        }

        Route apiListWithVersion() {
            return
                    // #optional-parameter
                    get(() ->
                            path(segment("api").slash("list"), () ->
                                    parameterOptional("version", version ->
                                            complete(apiList(version)))
                            )
                    );
            // #optional-parameter
        }

        Route apiListItems() {
            return
                    // #parameter-list
                    get(() ->
                            path(segment("api").slash("list-items"), () ->
                                    parameterList("item", items ->
                                            complete(apiItems(items)))
                            )
                    );
            // #parameter-list
        }
    }

    @Test
    public void showFixed() {
        // #fixed-test
        TestRoute route = testRoute(new Routes().clientsAll());
        route.run(HttpRequest.GET("/clients/all"))
                .assertStatusCode(StatusCodes.OK)
                .assertEntity("clientA,clientB,clientC");
        // #fixed-test
    }

    @Test
    public void showLong() {
        // #long-test
        TestRoute route = testRoute(new Routes().clientById());
        route.run(HttpRequest.GET("/client/321433"))
                .assertStatusCode(StatusCodes.OK)
                .assertEntity("clientB");
        // #long-test
    }

    @Test
    public void showRemaining() {
        // #remaining-test
        TestRoute route = testRoute(new Routes().files());
        route.run(HttpRequest.GET("/files/images/logo.png"))
                .assertStatusCode(StatusCodes.OK)
                .assertEntity("images/logo.png: file contents");
        // #remaining-test
    }

    @Test
    public void showMandatoryParameter() {
        // #mandatory-parameter-test
        TestRoute route = testRoute(new Routes().pageParameter());
        route.run(HttpRequest.GET("?page=example.txt"))
                .assertStatusCode(StatusCodes.OK)
                .assertEntity("The requested [example.txt].");
        route.run(HttpRequest.GET("/"))
                .assertStatusCode(StatusCodes.NOT_FOUND);
        // #mandatory-parameter-test
    }

    @Test
    public void optionalParameter() {
        // #optional-parameter-test
        TestRoute route = testRoute(new Routes().apiListWithVersion());
        route.run(HttpRequest.GET("/api/list?version=3.0"))
                .assertStatusCode(StatusCodes.OK)
                .assertEntity("aa,bb,cc");
        route.run(HttpRequest.GET("/api/list"))
                .assertStatusCode(StatusCodes.OK)
                .assertEntity("ff");
        // #optional-parameter-test
    }

    @Test
    public void parameterList() {
        // #parameter-list-test
        TestRoute route = testRoute(new Routes().apiListItems());
        route.run(HttpRequest.GET("/api/list-items?item=red&item=new&item=slippers"))
                .assertStatusCode(StatusCodes.OK)
                .assertEntity("slippers,new,red"); // order is not kept
        // #parameter-list-test
    }
}
