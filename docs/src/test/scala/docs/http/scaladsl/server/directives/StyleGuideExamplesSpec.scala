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
      val over = concat(
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

      val prefer: Route =
        concat(
          pathPrefix("item")(itemRoutes),
          pathPrefix("customer")(customerRoutes)
        )

      // over
      val over: Route = concat(
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
      def completeWithReply() = complete("")

      // #directives-combine
      // prefer
      val getOrPost: Directive0 = get | post
      val withClientId: Directive1[(Long)] =
        parameter("clientId".as[Long])

      val prefer =
        concat(
          pathPrefix("data") {
            concat(
              path("customer") {
                withClientId { clientId =>
                  completeWithReply()
                }
              },
              path("engagement") {
                withClientId { clientId =>
                  completeWithReply()
                }
              })
          },
          pathPrefix("pages") {
            concat(
              path("page1") {
                getOrPost {
                  completeWithReply()
                }
              },
              path("page2") {
                getOrPost {
                  completeWithReply()
                }
              }
            )
          }
        )
      // over
      val over =
        concat(
          pathPrefix("data") {
            concat(
              (pathPrefix("customer") & parameter("clientId".as[Long])) { clientId =>
                completeWithReply()
              },
              (pathPrefix("engagement") & parameter("clientId".as[Long])) { clientId =>
                completeWithReply()
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
