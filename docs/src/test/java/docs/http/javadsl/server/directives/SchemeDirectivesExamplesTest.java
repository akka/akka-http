/*
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.model.headers.Location;

//#extractScheme
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.extractScheme;

//#extractScheme
//#scheme
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.extract;
import static akka.http.javadsl.server.Directives.redirect;
import static akka.http.javadsl.server.Directives.route;
import static akka.http.javadsl.server.Directives.scheme;

//#scheme

public class SchemeDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testScheme() {
    //#extractScheme
    final Route route = extractScheme((scheme) -> 
                                      complete(String.format("The scheme is '%s'", scheme)));
    testRoute(route).run(HttpRequest.GET("https://www.example.com/"))
      .assertEntity("The scheme is 'https'");
    //#extractScheme
  }

  @Test
  public void testRedirection() {
    //#scheme
    final Route route = concat(
      scheme("http", ()->
        extract((ctx) -> ctx.getRequest().getUri(), (uri)->
          redirect(uri.scheme("https"), StatusCodes.MOVED_PERMANENTLY)
        )
      ),
      scheme("https", ()->
        complete("Safe and secure!")
      )
    );

    testRoute(route).run(HttpRequest.GET("http://www.example.com/hello"))
      .assertStatusCode(StatusCodes.MOVED_PERMANENTLY)
      .assertHeaderExists(Location.create("https://www.example.com/hello"))
    ;

    testRoute(route).run(HttpRequest.GET("https://www.example.com/hello"))
      .assertEntity("Safe and secure!");
    //#scheme
  }

}
