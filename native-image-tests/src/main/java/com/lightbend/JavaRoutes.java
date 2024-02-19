/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend;

import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;

import static akka.http.javadsl.server.Directives.*;

public class JavaRoutes {

    static Route javaRoutes() {
        return extractLog(log ->
                path("jackson", () ->
                        post(() ->
                                entity(
                                        Jackson.unmarshaller(SomeJavaModel.class),
                                        (SomeJavaModel in) -> {
                                            log.info("Got jackson json {}", in);
                                            return complete(StatusCodes.OK, new SomeJavaModel("out", 23),
                                                    Jackson.marshaller());
                                        }
                                )
                        )
                )
        );
    }
}
