/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PlayRoutesComparisonSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  object Clients {
    def list(): String = "clientA,clientB,clientC"

    def get(id: Long): String = "clientB"
  }

  "Play examples" should {

    "show a fixed URL" in {
      val clientsAll: Route =
        // #fixed
        (get & path("clients" / "all")) {
          complete(Clients.list())
        }
      // #fixed

      // #fixed-test
      Get("/clients/all") ~> clientsAll ~> check {
        responseAs[String] shouldEqual "clientA,clientB,clientC"
      }
      // #fixed-test
    }

    "show reading a long" in {
      val clientById: Route =
        // #long
        (get & path("client" / LongNumber)) { id =>
          complete(Clients.get(id))
        }
      // #long

      // #long-test
      Get("/client/321433") ~> clientById ~> check {
        responseAs[String] shouldEqual "clientB"
      }
      // #long-test
    }

    "use multiple segments" in {
      def download(name: String) = s"$name: file contents"

      val files: Route =
        // #remaining
        (get & path("files" / Remaining)) { name =>
          complete(download(name))
        }
      // #remaining

      // #remaining-test
      Get("/files/images/logo.png") ~> files ~> check {
        responseAs[String] shouldEqual "images/logo.png: file contents"
      }
      // #remaining-test
    }

    "require a parameter" in {
      def getPage(name: String) = s"The requested [$name]."

      val pageParameter: Route =
        // #mandatory-parameter
        (get & path("") & parameter("page")) { page =>
          complete(getPage(page))
        }
      // #mandatory-parameter

      // #mandatory-parameter-test
      Get("/?page=example.txt") ~> pageParameter ~> check {
        responseAs[String] shouldEqual "The requested [example.txt]."
      }
      // #mandatory-parameter-test
    }

    "accept a parameter" in {
      def listAll(version: Option[String]) = version.fold("ff")(_ => "aa,bb,cc")

      val optionalPageParameter: Route =
        // #optional-parameter
        (get & path("api" / "list-all") & parameter("version".optional)) { version =>
          complete(listAll(version))
        }
      // #optional-parameter

      // #optional-parameter-test
      Get("/api/list-all?version=3.0") ~> optionalPageParameter ~> check {
        responseAs[String] shouldEqual "aa,bb,cc"
      }
      Get("/api/list-all") ~> optionalPageParameter ~> check {
        responseAs[String] shouldEqual "ff"
      }
      // #optional-parameter-test
    }

    "accept a parameter list" in {
      def listItems(items: Iterable[String]) = items.mkString(",")

      val itemParameterList: Route =
        // #parameter-list
        (get & path("api" / "list-items") & parameters("item".repeated)) { items =>
          complete(listItems(items))
        }
      // #parameter-list

      // #parameter-list-test
      Get("/api/list-items?item=red&item=new&item=slippers") ~> itemParameterList ~> check {
        responseAs[String] shouldEqual "slippers,new,red"
      }
      // #parameter-list-test
    }
  }
}
