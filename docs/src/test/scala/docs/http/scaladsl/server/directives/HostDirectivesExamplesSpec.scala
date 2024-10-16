/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import headers._
import StatusCodes._
import akka.http.scaladsl.server.RoutingSpec
import docs.CompileOnlySpec

class HostDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {

  "extractHost" in {
    //#extractHost
    val route =
      extractHost { hn =>
        complete(s"Hostname: $hn")
      }

    // tests:
    Get() ~> Host("company.com", 9090) ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "Hostname: company.com"
    }
    //#extractHost
  }

  "list-of-hosts" in {
    //#list-of-hosts
    val route =
      host("api.company.com", "rest.company.com") {
        complete("Ok")
      }

    // tests:
    Get() ~> Host("rest.company.com") ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "Ok"
    }

    Get() ~> Host("notallowed.company.com") ~> route ~> check {
      handled shouldBe false
    }
    //#list-of-hosts
  }

  "predicate" in {
    //#predicate
    val shortOnly: String => Boolean = (hostname) => hostname.length < 10

    val route =
      host(shortOnly) {
        complete("Ok")
      }

    // tests:
    Get() ~> Host("short.com") ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "Ok"
    }

    Get() ~> Host("verylonghostname.com") ~> route ~> check {
      handled shouldBe false
    }
    //#predicate
  }

  "using-regex" in {
    //#using-regex
    val route =
      concat(
        host("api|rest".r) { prefix =>
          complete(s"Extracted prefix: $prefix")
        },
        host("public.(my|your)company.com".r) { captured =>
          complete(s"You came through $captured company")
        }
      )

    // tests:
    Get() ~> Host("api.company.com") ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "Extracted prefix: api"
    }

    Get() ~> Host("public.mycompany.com") ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "You came through my company"
    }
    //#using-regex
  }

  "failing-regex" in {
    //#failing-regex
    an[IllegalArgumentException] should be thrownBy {
      host("server-([0-9]).company.(com|net|org)".r) { target =>
        complete("Will never complete :'(")
      }
    }
    //#failing-regex
  }

}
