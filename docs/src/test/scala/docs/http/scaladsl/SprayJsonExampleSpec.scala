/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import scala.annotation.nowarn
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

@nowarn("msg=will not be a runnable program")
class SprayJsonExampleSpec extends AnyWordSpec with Matchers {

  def compileOnlySpec(body: => Unit) = ()

  "spray-json example" in compileOnlySpec {
    //#minimal-spray-json-example
    import akka.http.scaladsl.server.Directives
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
    import spray.json._

    // domain model
    final case class Item(name: String, id: Long)
    final case class Order(items: List[Item])

    // collect your json format instances into a support trait:
    trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
      implicit val itemFormat: RootJsonFormat[Item] = jsonFormat2(Item.apply)
      implicit val orderFormat: RootJsonFormat[Order] = jsonFormat1(Order.apply) // contains List[Item]
    }

    // use it wherever json (un)marshalling is needed
    class MyJsonService extends Directives with JsonSupport {

      val route =
        concat(
          get {
            pathSingleSlash {
              complete(Item("thing", 42)) // will render as JSON
            }
          },
          post {
            entity(as[Order]) { order => // will unmarshal JSON to Order
              val itemsCount = order.items.size
              val itemNames = order.items.map(_.name).mkString(", ")
              complete(s"Ordered $itemsCount items: $itemNames")
            }
          }
        )
    }
    //#minimal-spray-json-example
  }
}
