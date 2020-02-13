/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

/**
 * @groupname concat Route concatenation
 * @groupprio concat 300
 */
trait RouteConcatenation {

  /**
   * @group concat
   */
  implicit def _enhanceRouteWithConcatenation(route: Route): RouteConcatenation.RouteWithConcatenation =
    new RouteConcatenation.RouteWithConcatenation(route: Route)

  /**
   * Tries the supplied routes in sequence, returning the result of the first route that doesn't reject the request.
   * This is an alternative to direct usage of the infix ~ operator. The ~ can be prone to programmer error, because if
   * it is omitted, the program will still be syntactically correct, but will not actually attempt to match multiple
   * routes, as intended.
   *
   * @param routes subroutes to concatenate
   * @return the concatenated route
   */
  def concat(routes: Route*): Route = AlternativeRoutes(routes.toList)
}

object RouteConcatenation extends RouteConcatenation {

  class RouteWithConcatenation(route: Route) {
    /**
     * Returns a Route that chains two Routes. If the first Route rejects the request the second route is given a
     * chance to act upon the request.
     */
    def ~(other: Route): Route = concat(route, other)
  }
}
