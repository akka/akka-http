
package akka.http.javadsl.server.directives

import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.directives.{ CorsDirectives => CD }
import akka.http.javadsl.settings.CorsSettings

import java.util.function.Supplier

abstract class CorsDirectives extends FramedEntityStreamingDirectives {
  import akka.http.javadsl.server.RoutingJavaMapping.Implicits._
  import akka.http.javadsl.server.RoutingJavaMapping._

  /**
   * Wraps its inner route with support for the CORS mechanism, enabling cross origin requests.
   *
   * The settings are loaded from the Actor System configuration.
   */
  def cors(inner: Supplier[Route]): Route = RouteAdapter {
    CD.cors() {
      inner.get().delegate
    }
  }

  /**
   * Wraps its inner route with support for the CORS mechanism, enabling cross origin requests using the given cors
   * settings.
   */
  def cors(settings: CorsSettings, inner: Supplier[Route]): Route = RouteAdapter {
    CD.cors(settings.asInstanceOf[akka.http.scaladsl.settings.CorsSettings]) {
      inner.get().delegate
    }
  }
}
