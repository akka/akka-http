/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

//#seal-handler-example
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.ExceptionHandler;
import akka.http.javadsl.server.PathMatchers;
import akka.http.javadsl.server.RejectionHandler;
import akka.http.javadsl.server.Route;

import static akka.http.javadsl.server.PathMatchers.integerSegment;

public class ExceptionHandlerInSealExample extends AllDirectives {

  public Route createRoute() {
    final ExceptionHandler divByZeroHandler = ExceptionHandler.newBuilder()
      .match(ArithmeticException.class, x ->
        complete(StatusCodes.BAD_REQUEST, "You've got your arithmetic wrong, fool!"))
      .build();

    final RejectionHandler defaultHandler = RejectionHandler.defaultHandler();

    return path(PathMatchers.segment("divide").slash(integerSegment()).slash(integerSegment()), (a, b) ->
      complete("The result is " + (a / b))
    ).seal(defaultHandler, divByZeroHandler);
  }
}
//#seal-handler-example
