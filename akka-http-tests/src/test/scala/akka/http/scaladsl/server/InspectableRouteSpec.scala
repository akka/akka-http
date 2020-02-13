/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.impl.util.AkkaSpecWithMaterializer
import Directives._
import DynamicDirective._
import DirectiveRoute.addByNameNullaryApply

class InspectableRouteSpec extends AkkaSpecWithMaterializer {
  "Routes" should {
    "be inspectable" should {
      "routes from method directives" in {
        val route = get { post { complete("ok") } }
        route shouldBe an[InspectableRoute]
        println(route)
      }
      "route alternatives with ~" in {
        val route = get { complete("ok") } ~ post { complete("ok") }
        route shouldBe an[InspectableRoute]
        println(route)
      }
      "route alternatives with concat" in {
        val route =
          // semantic change here: the whole routing tree is evaluated eagerly because of the alternative
          // `DirectiveRoute.addByNameNullaryApply` imported above
          concat(
            get { complete("ok") },
            post { complete("ok") }
          )
        route shouldBe an[InspectableRoute]
      }
      "for routes with extractions" in {
        val route =
          // if you use static, it lifts the value into a token, that's the main API change for users
          parameters("name").static { name: ExtractionToken[String] =>
            get {
              // you can only access token values inside of dynamic blocks
              // it's not possible to inspect inner routes of dynamic blocks
              dynamic { implicit extractionCtx =>
                // with an implicit ExtractionContext in scope you can access token values using an implicit conversion
                complete(s"Hello ${name: String}")
              }
            }
          }
        route shouldBe an[InspectableRoute]
        println(route)
      }
    }
  }
}
