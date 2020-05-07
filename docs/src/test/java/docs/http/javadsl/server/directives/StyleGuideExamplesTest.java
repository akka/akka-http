/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

// #imports
import static akka.http.javadsl.server.PathMatchers.*;
import static akka.http.javadsl.server.Directives.*;

import akka.http.javadsl.server.Route;

// #imports

// #imports-directives
import java.util.function.Function;
import java.util.function.Supplier;

import akka.http.javadsl.unmarshalling.StringUnmarshallers;

// #imports-directives

public class StyleGuideExamplesTest {

    public void pathShouldBeOutermost() {
        // #path-outermost
        // prefer
        Route prefer =
            path(segment("item").slash("listing"), () ->
                get(() ->
                    complete("")
                )
            );
        // over
        Route over =
            get(() ->
                path(segment("item").slash("listing"), () ->
                    complete("")
                )
            );
        // #path-outermost
    }

    public void pathShouldUsePrefix() {
        // #path-prefix
        // prefer
        Route prefer =
            pathPrefix("item", () ->
                concat(
                    path("listing", () ->
                        get(() ->
                            complete("")
                        )
                    ),
                    path(segment("show").slash(segment()), itemId ->
                        get(() ->
                            complete("")
                        )
                    )
                )
            );
        // over
        Route over = concat(
            path(segment("item").slash("listing"), () ->
                get(() ->
                    complete("")
                )),
            path(segment("item").slash("show").slash(segment()), itemId ->
                get(() ->
                    complete("")
                )
            )
        );
        // #path-prefix
    }

    public void pathShouldSplit() {
        // #path-compose
        // prefer
        // 1. First, create partial matchers (with a relative path)
        Route itemRoutes =
            concat(
                path("listing", () ->
                    get(() ->
                        complete("")
                    )
                ),
                path(segment("show").slash(segment()), itemId ->
                    get(() ->
                        complete("")
                    )
                )
            );

        Route customerRoutes =
            concat(
                path(integerSegment(), customerId ->
                    complete("")
                )
                // ...
            );

        // 2. Then compose the relative routes under their corresponding path prefix
        Route prefer =
            concat(
                pathPrefix("item", () -> itemRoutes),
                pathPrefix("customer", () -> customerRoutes)
            );

        // over
        Route over = concat(
            pathPrefix("item", () ->
                concat(
                    path("listing", () ->
                        get(() ->
                            complete("")
                        )
                    ),
                    path(segment("show").slash(segment()), itemId ->
                        get(() ->
                            complete("")
                        )
                    )
                )
            ),
            pathPrefix("customer", () ->
                concat(
                    path(integerSegment(), customerId ->
                        complete("")
                    )
                    // ...
                )
            )
        );
        // #path-compose
    }

    // #directives-combine
    // prefer
    Route getOrPost(Supplier<Route> inner) {
        return get(inner)
            .orElse(post(inner));
    }

    Route withCustomerId(Function<Long, Route> useCustomerId) {
        return parameter(StringUnmarshallers.LONG, "customerId", useCustomerId);
    }

    // #directives-combine

    public void directivesCombine() {
        // #directives-combine
        Function<Long, Route> useCustomerIdForResponse = (customerId) -> complete(customerId.toString());
        Supplier<Route> completeWithResponse = () -> complete("");

        Route prefer =
            concat(
                pathPrefix("data", () ->
                    concat(
                        path("customer", () ->
                            withCustomerId(useCustomerIdForResponse)
                        ),
                        path("engagement", () ->
                            withCustomerId(useCustomerIdForResponse)
                        )
                    )
                ),
                pathPrefix("pages", () ->
                    concat(
                        path("page1", () ->
                            getOrPost(completeWithResponse)
                        ),
                        path("page2", () ->
                            getOrPost(completeWithResponse)
                        )
                    )
                )
            );
        // over
        Route over =
            concat(
                pathPrefix("data", () ->
                    concat(
                        pathPrefix("customer", () ->
                            parameter(StringUnmarshallers.LONG, "customerId", customerId ->
                                complete(customerId.toString())
                            )
                        ),
                        pathPrefix("engagement", () ->
                            parameter(StringUnmarshallers.LONG, "customerId", customerId ->
                                complete(customerId.toString())
                            )
                        )
                    )
                ),
                pathPrefix("pages", () ->
                    concat(
                        path("page1", () ->
                            concat(
                                get(() ->
                                    complete("")
                                ),
                                post(() ->
                                    complete("")
                                )
                            )
                        ),
                        path("page2", () ->
                            get(() ->
                                complete("")
                            ).orElse(post(() ->
                                complete("")))
                        )
                    )
                )
            );
        // #directives-combine
    }
}
