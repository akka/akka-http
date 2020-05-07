/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.server._
import docs.CompileOnlySpec

class StyleGuideExamplesSpec extends RoutingSpec with CompileOnlySpec {

  "path" should {

    "be outermost" in {
      // #path-outermost
      // prefer
      val prefer = path("item" / "listing") & get
      // over
      val over = get & path("item" / "listing")
      // #path-outermost
    }

    "use prefix" in {
      // #path-prefix
      // prefer
      val prefer =
        pathPrefix("item") {
          concat(
            path("listing") {
              get {
                complete("")
              }
            },
            path("show" / Segment) { itemId =>
              get {
                complete("")
              }
            }
          )
        }
      // over
      val over: Route =
        concat(
          path("item" / "listing") {
            get {
              complete("")
            }
          },
          path("item" / "show" / Segment) { itemId =>
            get {
              complete("")
            }
          }
        )
      // #path-prefix
    }

    "split" in {
      // #path-compose
      // prefer
      // 1. First, create partial matchers (with a relative path)
      val itemRoutes: Route =
        concat(
          path("listing") {
            get {
              complete("")
            }
          },
          path("show" / Segment) { itemId =>
            get {
              complete("")
            }
          }
        )

      val customerRoutes: Route =
        concat(
          path("customer" / IntNumber) { customerId =>
            complete("")
          }
        // ...
        )

      // 2. Then compose the relative routes under their corresponding path prefix
      val prefer: Route =
        concat(
          pathPrefix("item")(itemRoutes),
          pathPrefix("customer")(customerRoutes)
        )

      // over
      val over: Route =
        concat(
          pathPrefix("item") {
            concat(
              path("listing") {
                get {
                  complete("")
                }
              },
              path("show" / Segment) { itemId =>
                get {
                  complete("")
                }
              }
            )
          },
          pathPrefix("customer") {
            concat(
              path("customer" / IntNumber) { cosumerId =>
                complete("")
              }
            // ...
            )
          }
        )
      // #path-compose
    }
  }

  "directives" should {
    "be combined" in {

      // #directives-combine
      val useCustomerIdForResponse: Long => Route = (customerId) => complete(customerId.toString)
      val completeWithResponse: Route = complete("")

      // prefer
      val getOrPost: Directive0 = get | post
      val withCustomerId: Directive1[(Long)] =
        parameter("customerId".as[Long])

      val prefer: Route =
        concat(
          pathPrefix("data") {
            concat(
              path("customer") {
                withCustomerId(useCustomerIdForResponse)
              },
              path("engagement") {
                withCustomerId(useCustomerIdForResponse)
              })
          },
          pathPrefix("pages") {
            concat(
              path("page1") {
                getOrPost(completeWithResponse)
              },
              path("page2") {
                getOrPost(completeWithResponse)
              }
            )
          }
        )
      // over
      val over: Route =
        concat(
          pathPrefix("data") {
            concat(
              (pathPrefix("customer") & parameter("customerId".as[Long])) { customerId =>
                useCustomerIdForResponse(customerId)
              },
              (pathPrefix("engagement") & parameter("customerId".as[Long])) { customerId =>
                useCustomerIdForResponse(customerId)
              }
            )
          },
          pathPrefix("pages") {
            concat(
              path("page1") {
                concat(
                  get {
                    complete("")
                  },
                  post {
                    complete("")
                  }
                )
              },
              path("page2") {
                (get | post) {
                  complete("")
                }
              }
            )
          }
        )
      // #directives-combine
    }
  }

}
