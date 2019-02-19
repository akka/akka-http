/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.RoutingSpec
import docs.CompileOnlySpec

class SprayJsonPrettyMarshalSpec extends RoutingSpec with CompileOnlySpec {

  "spray-json example" in {
    //#example
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    import spray.json._

    // domain model
    final case class PrettyPrintedItem(name: String, id: Long)

    object PrettyJsonFormatSupport {
      import DefaultJsonProtocol._
      implicit val printer = PrettyPrinter
      implicit val prettyPrintedItemFormat = jsonFormat2(PrettyPrintedItem)
    }

    // use it wherever json (un)marshalling is needed
    class MyJsonService extends Directives {
      import PrettyJsonFormatSupport._

      // format: OFF
      val route =
        get {
          pathSingleSlash {
            complete {
              PrettyPrintedItem("akka", 42) // will render as JSON
            }
          }
        }
      // format: ON
    }

    val service = new MyJsonService

    // verify the pretty printed JSON
    Get("/") ~> service.route ~> check {
      responseAs[String] shouldEqual
        """{""" + "\n" +
        """  "id": 42,""" + "\n" +
        """  "name": "akka"""" + "\n" +
        """}"""
    }
    //#example
  }
}
