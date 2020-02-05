/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import org.scalatest.Inside
import akka.http.scaladsl.unmarshalling.Unmarshaller, Unmarshaller._
import akka.http.scaladsl.model.StatusCodes
import org.scalatest.freespec.AnyFreeSpec

class ParameterDirectivesSpec extends AnyFreeSpec with GenericRoutingSpec with Inside {
  "when used with 'as[Int]' the parameter directive should" - {
    "extract a parameter value as Int" in {
      Get("/?amount=123") ~> {
        parameter('amount.as[Int]) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "123" }
    }
    "cause a MalformedQueryParamRejection on illegal Int values" in {
      Get("/?amount=1x3") ~> {
        parameter('amount.as[Int]) { echoComplete }
      } ~> check {
        inside(rejection) {
          case MalformedQueryParamRejection("amount", "'1x3' is not a valid 32-bit signed integer value", Some(_)) =>
        }
      }
    }
    "supply typed default values" in {
      Get() ~> {
        parameter('amount ? 45) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "45" }
    }
    "create typed optional parameters that" - {
      "extract Some(value) when present" in {
        Get("/?amount=12") ~> {
          parameter("amount".as[Int].?) { echoComplete }
        } ~> check { responseAs[String] shouldEqual "Some(12)" }
      }
      "extract None when not present" in {
        Get() ~> {
          parameter("amount".as[Int].?) { echoComplete }
        } ~> check { responseAs[String] shouldEqual "None" }
      }
      "cause a MalformedQueryParamRejection on illegal Int values" in {
        Get("/?amount=x") ~> {
          parameter("amount".as[Int].?) { echoComplete }
        } ~> check {
          inside(rejection) {
            case MalformedQueryParamRejection("amount", "'x' is not a valid 32-bit signed integer value", Some(_)) =>
          }
        }
      }
      "cause a MalformedRequestContentRejection on invalid query strings" in {
        Get("/?amount=1%2") ~> {
          parameter("amount".as[Int].?) { echoComplete }
        } ~> check {
          inside(rejection) {
            case MalformedRequestContentRejection("The request's query string is invalid.", _) =>
          }
        }
      }
    }
    "supply chaining of unmarshallers" in {
      case class UserId(id: Int)
      case class AnotherUserId(id: Int)
      val UserIdUnmarshaller = Unmarshaller.strict[Int, UserId](UserId)
      implicit val AnotherUserIdUnmarshaller = Unmarshaller.strict[UserId, AnotherUserId](userId => AnotherUserId(userId.id))
      Get("/?id=45") ~> {
        parameter('id.as[Int].as(UserIdUnmarshaller)) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "UserId(45)" }
      Get("/?id=45") ~> {
        parameter('id.as[Int].as(UserIdUnmarshaller).as[AnotherUserId]) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "AnotherUserId(45)" }
    }
  }

  "when used with 'as(CsvSeq[...])' the parameter directive should" - {
    val route =
      parameter("names".as(CsvSeq[String])) { names =>
        complete(s"The parameters are ${names.mkString(", ")}")
      }

    "extract a single name" in {
      Get("/?names=Caplin") ~> route ~> check {
        responseAs[String] shouldEqual "The parameters are Caplin"
      }
    }
    "extract a number of names" in {
      Get("/?names=Caplin,John") ~> route ~> check {
        responseAs[String] shouldEqual "The parameters are Caplin, John"
      }
    }
    "extract a number of names, including last empty name" in {
      Get("/?names=Caplin,John,") ~> route ~> check {
        responseAs[String] shouldEqual "The parameters are Caplin, John, "
      }
    }
  }

  "when used with 'as(HexInt)' the parameter directive should" - {
    "extract parameter values as Int" in {
      Get("/?amount=1f") ~> {
        parameter('amount.as(HexInt)) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "31" }
    }
    "cause a MalformedQueryParamRejection on illegal Int values" in {
      Get("/?amount=1x3") ~> {
        parameter('amount.as(HexInt)) { echoComplete }
      } ~> check {
        inside(rejection) {
          case MalformedQueryParamRejection("amount", "'1x3' is not a valid 32-bit hexadecimal integer value", Some(_)) =>
        }
      }
    }
    "supply typed default values" in {
      Get() ~> {
        parameter('amount.as(HexInt) ? 45) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "45" }
    }
    "create typed optional parameters that" - {
      "extract Some(value) when present" in {
        Get("/?amount=A") ~> {
          parameter("amount".as(HexInt).?) { echoComplete }
        } ~> check { responseAs[String] shouldEqual "Some(10)" }
      }
      "extract None when not present" in {
        Get() ~> {
          parameter("amount".as(HexInt).?) { echoComplete }
        } ~> check { responseAs[String] shouldEqual "None" }
      }
      "cause a MalformedQueryParamRejection on illegal Int values" in {
        Get("/?amount=x") ~> {
          parameter("amount".as(HexInt).?) { echoComplete }
        } ~> check {
          inside(rejection) {
            case MalformedQueryParamRejection("amount", "'x' is not a valid 32-bit hexadecimal integer value", Some(_)) =>
          }
        }
      }
    }
  }

  "when used with 'as[Boolean]' the parameter directive should" - {
    "extract parameter values as Boolean" in {
      Get("/?really=true") ~> {
        parameter('really.as[Boolean]) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "true" }
      Get("/?really=no") ~> {
        parameter('really.as[Boolean]) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "false" }
    }
    "extract numeric parameter value as Boolean" in {
      Get("/?really=1") ~> {
        parameter('really.as[Boolean]) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "true" }
      Get("/?really=0") ~> {
        parameter('really.as[Boolean]) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "false" }
    }
    "extract optional parameter values as Boolean" in {
      Get() ~> {
        parameter('really.as[Boolean] ? false) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "false" }
    }
    "cause a MalformedQueryParamRejection on illegal Boolean values" in {
      Get("/?really=absolutely") ~> {
        parameter('really.as[Boolean]) { echoComplete }
      } ~> check {
        inside(rejection) {
          case MalformedQueryParamRejection("really", "'absolutely' is not a valid Boolean value", None) =>
        }
      }
    }
  }

  "The 'parameters' extraction directive should" - {
    "extract the value of given parameters" in {
      Get("/?name=Parsons&FirstName=Ellen") ~> {
        parameters("name", 'FirstName) { (name, firstName) =>
          complete(firstName + name)
        }
      } ~> check { responseAs[String] shouldEqual "EllenParsons" }
    }
    "correctly extract an optional parameter" in {
      Get("/?foo=bar") ~> parameters('foo.?) { echoComplete } ~> check { responseAs[String] shouldEqual "Some(bar)" }
      Get("/?foo=bar") ~> parameters('baz.?) { echoComplete } ~> check { responseAs[String] shouldEqual "None" }
    }
    "ignore additional parameters" in {
      Get("/?name=Parsons&FirstName=Ellen&age=29") ~> {
        parameters("name", 'FirstName) { (name, firstName) =>
          complete(firstName + name)
        }
      } ~> check { responseAs[String] shouldEqual "EllenParsons" }
    }
    "reject the request with a MissingQueryParamRejection if a required parameter is missing" in {
      Get("/?name=Parsons&sex=female") ~> {
        parameters('name, 'FirstName, 'age) { (name, firstName, age) =>
          completeOk
        }
      } ~> check { rejection shouldEqual MissingQueryParamRejection("FirstName") }
    }
    "supply the default value if an optional parameter is missing" in {
      Get("/?name=Parsons&FirstName=Ellen") ~> {
        parameters("name".?, 'FirstName, 'age ? "29", 'eyes.?) { (name, firstName, age, eyes) =>
          complete(firstName + name + age + eyes)
        }
      } ~> check { responseAs[String] shouldEqual "EllenSome(Parsons)29None" }
    }
  }

  "The 'parameter' requirement directive should" - {
    "reject the request with a MissingQueryParamRejection if request do not contain the required parameter" in {
      Get("/person?age=19") ~> {
        parameter('nose ! "large") { completeOk }
      } ~> check { rejection shouldEqual MissingQueryParamRejection("nose") }
    }
    "reject the request with a InvalidRequiredValueForQueryParamRejection if the required parameter has an unmatching value" in {
      Get("/person?age=19&nose=small") ~> {
        parameter('nose ! "large") { completeOk }
      } ~> check { rejection shouldEqual InvalidRequiredValueForQueryParamRejection("nose", "large", "small") }
    }
    "let requests pass that contain the required parameter with its required value" in {
      Get("/person?nose=large&eyes=blue") ~> {
        parameter('nose ! "large") { completeOk }
      } ~> check { response shouldEqual Ok }
    }
    "be useable for method tunneling" in {
      val route = {
        (post | parameter('method ! "post")) { complete("POST") } ~
          get { complete("GET") }
      }
      Get("/?method=post") ~> route ~> check { responseAs[String] shouldEqual "POST" }
      Post() ~> route ~> check { responseAs[String] shouldEqual "POST" }
      Get() ~> route ~> check { responseAs[String] shouldEqual "GET" }
    }
  }

  "The 'parameter' repeated directive should" - {
    "extract an empty Iterable when the parameter is absent" in {
      Get("/person?age=19") ~> {
        parameter('hobby.*) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "List()" }
    }
    "extract all occurrences into an Iterable when parameter is present" in {
      Get("/person?age=19&hobby=cooking&hobby=reading") ~> {
        parameter('hobby.*) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "List(reading, cooking)" }
    }
    "extract as Iterable[Int]" in {
      Get("/person?age=19&number=3&number=5") ~> {
        parameter('number.as[Int].*) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "List(5, 3)" }
    }
    "extract as Iterable[Int] with an explicit deserializer" in {
      Get("/person?age=19&number=3&number=A") ~> {
        parameter('number.as(HexInt).*) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "List(10, 3)" }
    }
  }

  "The 'parameterSeq' directive should" - {
    val completeAsList =
      parameterSeq { params =>
        val sorted = params.sorted
        complete(s"${sorted.size}: [${sorted.map(e => e._1 + " -> " + e._2).mkString(", ")}]")
      }

    "extract parameters with different keys" in {
      Get("/?a=b&e=f&c=d") ~> completeAsList ~> check {
        responseAs[String] shouldEqual "3: [a -> b, c -> d, e -> f]"
      }
    }
    "extract parameters with duplicate keys" in {
      Get("/?a=b&e=f&c=d&a=z") ~> completeAsList ~> check {
        responseAs[String] shouldEqual "4: [a -> b, a -> z, c -> d, e -> f]"
      }
    }
  }

  "when used with 'as[A](constructor)' the parameter directive should" - {
    "extract a parameter value as Case Class" in {
      case class Color(red: Int, green: Int, blue: Int)
      Get("/?red=90&green=50&blue=0") ~> {
        parameter('red.as[Int], 'green.as[Int], 'blue.as[Int]).as(Color) { color =>
          complete(s"${color.red} ${color.green} ${color.blue}")
        }
      } ~> check { responseAs[String] shouldEqual "90 50 0" }
    }
    "reject the request with a ValidationRejection if a parameter value violate requirements" in {
      case class Color(red: Int, green: Int, blue: Int) {
        require(0 <= red && red <= 255)
        require(0 <= green && green <= 255)
        require(0 <= blue && blue <= 255)
      }
      Get("/?red=500&green=0&blue=0") ~> {
        parameter('red.as[Int], 'green.as[Int], 'blue.as[Int]).as(Color) { color =>
          complete(s"${color.red} ${color.green} ${color.blue}")
        }
      } ~> check {
        rejection should matchPattern { case ValidationRejection("requirement failed", _) => }
      }
    }
    "fail the request with InternalServerError if an IllegalArgumentException happens for another reason" in {
      case class Color(red: Int, green: Int, blue: Int) {
        require(0 <= red && red <= 255)
        require(0 <= green && green <= 255)
        require(0 <= blue && blue <= 255)
      }
      Get("/?red=0&green=0&blue=0") ~> {
        parameter('red.as[Int], 'green.as[Int], 'blue.as[Int]).as(Color) { _ =>
          throw new IllegalArgumentException
        }
      } ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
    }
  }
}
