/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import scala.collection.JavaConverters._

class RejectionSpec extends RoutingSpec {

  "The Transformation Rejection" should {

    "map to and from Java" in {
      import akka.http.javadsl.{ server => jserver }
      val rejections = List(RequestEntityExpectedRejection)
      val jrejections: java.lang.Iterable[jserver.Rejection] =
        rejections.map(_.asInstanceOf[jserver.Rejection]).asJava
      val jresult = TransformationRejection(identity).getTransform.apply(jrejections)

      val result = jresult.asScala.map(r => r.asInstanceOf[Rejection])
      result should ===(rejections)
    }
  }

  "RejectionHandler.default" which {
    import akka.http.scaladsl.model._

    implicit def myRejectionHandler: RejectionHandler =
      RejectionHandler.default
        .mapRejectionResponse {
          case res @ HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
            val message = ent.data.utf8String.replaceAll("\"", """\"""")
            res.withEntity(HttpEntity(ContentTypes.`application/json`, s"""{"rejection": "$message"}"""))

          case x => x // pass through all other types of responses
        }

    val route =
      Route.seal(
        path("hello") {
          complete("Hello there")
        } ~
          path("unsupported-content-type") {
            parameters(("provide-content-type".as[Boolean], "provide-supported".as[Boolean])) {
              (provideContentType, provideSupported) =>

                val supported =
                  if (provideSupported) Set[ContentTypeRange](MediaTypes.`image/jpeg`, MediaTypes.`image/png`)
                  else Set.empty[ContentTypeRange]

                val contentType =
                  if (provideContentType) Some[ContentType](MediaTypes.`image/gif`)
                  else None

                reject(UnsupportedRequestContentTypeRejection(supported, contentType))
            }
          }
      )

    "mapRejectionResponse" should {
      "not affect normal responses" in {
        Get("/hello") ~> route ~> check {
          status should ===(StatusCodes.OK)
          contentType should ===(ContentTypes.`text/plain(UTF-8)`)
          responseAs[String] should ===("""Hello there""")
        }
      }
      "alter rejection response" in {
        Get("/nope") ~> route ~> check {
          status should ===(StatusCodes.NotFound)
          contentType should ===(ContentTypes.`application/json`)
          responseAs[String] should ===("""{"rejection": "The requested resource could not be found."}""")
        }
      }
    }
    "UnsupportedRequestContentTypeRejection" should {
      "format error message without contentType and supported ranges" in {
        Get("/unsupported-content-type?provide-content-type=false&provide-supported=false") ~> route ~> check {
          status should ===(StatusCodes.UnsupportedMediaType)
          contentType should ===(ContentTypes.`application/json`)
          responseAs[String] should ===("""{"rejection": "The request's Content-Type is not supported."}""")
        }
      }
      "format error message with contentType and without supported ranges" in {
        Get("/unsupported-content-type?provide-content-type=true&provide-supported=false") ~> route ~> check {
          status should ===(StatusCodes.UnsupportedMediaType)
          contentType should ===(ContentTypes.`application/json`)
          responseAs[String] should ===("""{"rejection": "The request's Content-Type [image/gif] is not supported."}""")
        }
      }
      "format error message without contentType and with supported ranges" in {
        Get("/unsupported-content-type?provide-content-type=false&provide-supported=true") ~> route ~> check {
          status should ===(StatusCodes.UnsupportedMediaType)
          contentType should ===(ContentTypes.`application/json`)
          responseAs[String] should ===(
            """|{"rejection": "The request's Content-Type is not supported. Expected:
               |image/jpeg or image/png"}""".stripMargin)
        }
      }
      "format error message with contentType and with supported ranges" in {
        Get("/unsupported-content-type?provide-content-type=true&provide-supported=true") ~> route ~> check {
          status should ===(StatusCodes.UnsupportedMediaType)
          contentType should ===(ContentTypes.`application/json`)
          responseAs[String] should ===(
            """|{"rejection": "The request's Content-Type [image/gif] is not supported. Expected:
               |image/jpeg or image/png"}""".stripMargin)
        }
      }
    }
  }
}
