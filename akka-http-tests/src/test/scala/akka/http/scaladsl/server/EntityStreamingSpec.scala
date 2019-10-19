/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.NotUsed
import akka.http.scaladsl.common.{ EntityStreamingSupport, JsonEntityStreamingSupport }
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream.scaladsl._
import akka.testkit.EventFilter
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future

class EntityStreamingSpec extends RoutingSpec with ScalaFutures {

  //#models
  case class Tweet(uid: Int, txt: String)
  case class Measurement(id: String, value: Int)
  //#models

  val tweets = List(
    Tweet(1, "#Akka rocks!"),
    Tweet(2, "Streaming is so hot right now!"),
    Tweet(3, "You cannot enter the same river twice."))
  def getTweets = Source(tweets)

  object MyJsonProtocol
    extends akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
    with spray.json.DefaultJsonProtocol {

    implicit val tweetFormat = jsonFormat2(Tweet.apply)
    implicit val measurementFormat = jsonFormat2(Measurement.apply)
  }

  "spray-json-response-streaming" in {
    import MyJsonProtocol._

    implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

    val route =
      path("tweets") {
        // [3] simply complete a request with a source of tweets:
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }

    // tests ------------------------------------------------------------
    val AcceptJson = Accept(MediaRange(MediaTypes.`application/json`))
    val AcceptXml = Accept(MediaRange(MediaTypes.`text/xml`))

    Get("/tweets").withHeaders(AcceptJson) ~> route ~> check {
      responseAs[String] shouldEqual
        """[""" +
        """{"txt":"#Akka rocks!","uid":1},""" +
        """{"txt":"Streaming is so hot right now!","uid":2},""" +
        """{"txt":"You cannot enter the same river twice.","uid":3}""" +
        """]"""
    }

    // endpoint can only marshal Json, so it will *reject* requests for application/xml:
    Get("/tweets").withHeaders(AcceptXml) ~> route ~> check {
      handled should ===(false)
      rejection should ===(UnacceptedResponseContentTypeRejection(Set(ContentTypes.`application/json`)))
    }
  }

  "line-by-line-json-response-streaming" in {
    import MyJsonProtocol._

    val newline = ByteString("\n")

    implicit val jsonStreamingSupport = EntityStreamingSupport.json()
      .withFramingRenderer(Flow[ByteString].map(sb => sb ++ newline))

    val route =
      path("tweets") {
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }

    // tests ------------------------------------------------------------
    val AcceptJson = Accept(MediaRange(MediaTypes.`application/json`))

    Get("/tweets").withHeaders(AcceptJson) ~> route ~> check {
      responseAs[String] shouldEqual
        """{"txt":"#Akka rocks!","uid":1}""" + "\n" +
        """{"txt":"Streaming is so hot right now!","uid":2}""" + "\n" +
        """{"txt":"You cannot enter the same river twice.","uid":3}""" + "\n"
    }
  }

  "client-consume-streaming-json" in {
    //#json-streaming-client-example
    import MyJsonProtocol._
    import akka.http.scaladsl.unmarshalling._
    import akka.http.scaladsl.common.EntityStreamingSupport
    import akka.http.scaladsl.common.JsonEntityStreamingSupport

    implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
      EntityStreamingSupport.json()

    val input = """{"uid":1,"txt":"#Akka rocks!"}""" + "\n" +
      """{"uid":2,"txt":"Streaming is so hot right now!"}""" + "\n" +
      """{"uid":3,"txt":"You cannot enter the same river twice."}"""

    val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, input))

    // unmarshal:
    val unmarshalled: Future[Source[Tweet, NotUsed]] =
      Unmarshal(response).to[Source[Tweet, NotUsed]]

    // flatten the Future[Source[]] into a Source[]:
    val source: Source[Tweet, Future[NotUsed]] =
      Source.fromFutureSource(unmarshalled)

    //#json-streaming-client-example
    // tests ------------------------------------------------------------
    val all = source.runWith(Sink.seq).futureValue
    all.head.uid should ===(1)
    all.head.txt should ===("#Akka rocks!")
    all.drop(1).head.uid should ===(2)
    all.drop(1).head.txt should ===("Streaming is so hot right now!")
    all.drop(2).head.uid should ===(3)
    all.drop(2).head.txt should ===("You cannot enter the same river twice.")
  }

  "client-consume-streaming-json-raw" in {
    //#json-streaming-client-example-raw
    import MyJsonProtocol._
    import akka.http.scaladsl.unmarshalling._
    import akka.http.scaladsl.common.EntityStreamingSupport
    import akka.http.scaladsl.common.JsonEntityStreamingSupport

    implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
      EntityStreamingSupport.json()

    val input = """{"uid":1,"txt":"#Akka rocks!"}""" + "\n" +
      """{"uid":2,"txt":"Streaming is so hot right now!"}""" + "\n" +
      """{"uid":3,"txt":"You cannot enter the same river twice."}"""

    val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, input))

    val value: Source[Tweet, Any] =
      response.entity.dataBytes
        .via(jsonStreamingSupport.framingDecoder) // pick your Framing (could be "\n" etc)
        .mapAsync(1)(bytes => Unmarshal(bytes).to[Tweet]) // unmarshal one by one

    //#json-streaming-client-example-raw

    // tests ------------------------------------------------------------
    val all = value.runWith(Sink.seq).futureValue
    all.head.uid should ===(1)
    all.head.txt should ===("#Akka rocks!")
    all.drop(1).head.uid should ===(2)
    all.drop(1).head.txt should ===("Streaming is so hot right now!")
    all.drop(2).head.uid should ===(3)
    all.drop(2).head.txt should ===("You cannot enter the same river twice.")

  }

  "csv-example" in {
    implicit val tweetAsCsv = Marshaller.strict[Tweet, ByteString] { t =>
      Marshalling.WithFixedContentType(ContentTypes.`text/csv(UTF-8)`, () => {
        val txt = t.txt.replaceAll(",", ".")
        val uid = t.uid
        ByteString(List(uid, txt).mkString(","))
      })
    }

    implicit val csvStreaming = EntityStreamingSupport.csv()

    val route =
      path("tweets") {
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }

    val AcceptCsv = Accept(MediaRange(MediaTypes.`text/csv`))

    Get("/tweets").withHeaders(AcceptCsv) ~> route ~> check {
      responseAs[String] shouldEqual
        "1,#Akka rocks!" + "\n" +
        "2,Streaming is so hot right now!" + "\n" +
        "3,You cannot enter the same river twice." + "\n"
    }
  }

  "opaque-marshaller-example" in {
    implicit val tweetAsOpaqueByteString = Marshaller.opaque[Tweet, ByteString] { t =>
      ByteString(s"""${t.uid},"Text: ${t.txt}"""")
    }

    implicit val csvStreaming = EntityStreamingSupport.csv()

    val route =
      path("tweets") {
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }

    // tests ------------------------------------------------------------
    val AcceptCsv = Accept(MediaRange(MediaTypes.`text/csv`))

    Get("/tweets").withHeaders(AcceptCsv) ~> route ~> check {
      responseAs[String] shouldEqual
        """1,"Text: #Akka rocks!"""" + "\n" +
        """2,"Text: Streaming is so hot right now!"""" + "\n" +
        """3,"Text: You cannot enter the same river twice."""" + "\n"
    }

    val AcceptJson = Accept(MediaRange(MediaTypes.`application/json`))

    // endpoint can only marshal CSV, so it will *reject* requests for application/json:
    Get("/tweets").withHeaders(AcceptJson) ~> route ~> check {
      handled should ===(false)
      rejection should ===(UnacceptedResponseContentTypeRejection(Set(ContentTypes.`text/csv(UTF-8)`)))
    }
  }

  "opaque-and-non-opaque-marshaller-example" in {
    // If both an opaque marshaller and a non-opaque marshaller with the right content type are present,
    // prefer the non-opaque marshaller (even if the opaque one comes first in the sequence).
    val tweetAsOpaqueByteString = Marshaller.opaque[Tweet, ByteString] { t =>
      ByteString(s"""${t.uid},"Text: ${t.txt}"""")
    }

    val tweetAsCsv = Marshaller.strict[Tweet, ByteString] { t =>
      Marshalling.WithFixedContentType(ContentTypes.`text/csv(UTF-8)`, () => {
        val txt = t.txt.replaceAll(",", ".")
        val uid = t.uid
        ByteString(List(uid, txt).mkString(","))
      })
    }

    implicit val tweetMarshaller = Marshaller.oneOf(tweetAsOpaqueByteString, tweetAsCsv)

    implicit val csvStreaming = EntityStreamingSupport.csv()

    val route =
      path("tweets") {
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }

    // tests ------------------------------------------------------------
    val AcceptCsv = Accept(MediaRange(MediaTypes.`text/csv`))

    Get("/tweets").withHeaders(AcceptCsv) ~> route ~> check {
      responseAs[String] shouldEqual
        "1,#Akka rocks!" + "\n" +
        "2,Streaming is so hot right now!" + "\n" +
        "3,You cannot enter the same river twice." + "\n"
    }
  }

  "spray-json-request-streaming" in {
    import MyJsonProtocol._
    implicit val jsonStreamingSupport = EntityStreamingSupport.json()

    val persistMetrics = Flow[Measurement]

    val route =
      path("metrics") {
        // [3] extract Source[Measurement, _]
        entity(asSourceOf[Measurement]) { measurements =>
          // alternative syntax:
          // entity(as[Source[Measurement, NotUsed]]) { measurements =>
          val measurementsSubmitted: Future[Int] =
            measurements
              .via(persistMetrics)
              .runFold(0) { (cnt, _) => cnt + 1 }

          complete {
            measurementsSubmitted.map(n => Map("msg" -> s"""Total metrics received: $n"""))
          }
        }
      }

    // tests ------------------------------------------------------------
    // uploading an array or newline separated values works out of the box
    val data = HttpEntity(
      ContentTypes.`application/json`,
      """
        |{"id":"temp","value":32}
        |{"id":"temp","value":31}
        |
      """.stripMargin)

    Post("/metrics", entity = data) ~> route ~> check {
      status should ===(StatusCodes.OK)
      responseAs[String] should ===("""{"msg":"Total metrics received: 2"}""")
    }

    // the FramingWithContentType will reject any content type that it does not understand:
    val xmlData = HttpEntity(
      ContentTypes.`text/xml(UTF-8)`,
      """|<data id="temp" value="32"/>
         |<data id="temp" value="31"/>""".stripMargin)

    Post("/metrics", entity = xmlData) ~> route ~> check {
      handled should ===(false)
      rejection should ===(
        UnsupportedRequestContentTypeRejection(
          Set(ContentTypes.`application/json`),
          Some(ContentTypes.`text/xml(UTF-8)`)))
    }
    //#spray-json-request-streaming
  }

  "throw when attempting to render a JSON Source of raw Strings" in {
    implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
      EntityStreamingSupport.json()
    val route =
      get {
        // This is wrong since we try to render JSON, but String is not a valid top level element
        // we need to provide an explicit Marshaller[String, ByteString] if we really want to render a list of strings.
        val results = Source(List("One", "Two", "Three"))
        complete(results)
      }

    try {
      Get("/") ~> route ~> check {
        EventFilter.error(pattern = "None of the available marshallings ", occurrences = 1) intercept {
          responseAs[String] // should fail
        }
      }
    } catch {
      case ex: java.lang.RuntimeException if ex.getCause != null =>
        val cause = ex.getCause
        cause.getClass should ===(classOf[akka.http.scaladsl.marshalling.NoStrictlyCompatibleElementMarshallingAvailableException[_]])
        cause.getMessage should include("Please provide an implicit `Marshaller[java.lang.String, HttpEntity]")
        cause.getMessage should include("that can render java.lang.String as [application/json]")
    }
  }

  "render a JSON Source of raw Strings if String => JsValue is provided" in {
    implicit val stringFormat = Marshaller[String, ByteString] { ec => s =>
      Future.successful {
        List(Marshalling.WithFixedContentType(ContentTypes.`application/json`, () =>
          ByteString("\"" + s + "\"")) // "raw string" to be rendered as json element in our stream must be enclosed by ""
        )
      }
    }

    implicit val jsonStreamingSupport =
      EntityStreamingSupport.json()

    val eventsRoute =
      get {
        val results = Source(List("One", "Two", "Three"))
        val r = ToResponseMarshallable(results).apply(Get("/"))(system.dispatcher)
        complete(r)
      }

    val `Accept:application/example` = Accept(MediaRange(MediaType.parse("application/example").right.get))
    Get("/").addHeader(`Accept:application/example`) ~> eventsRoute ~> check {
      val res = responseAs[String]

      res shouldEqual """["One","Two","Three"]"""

    }
  }

  "throw an exception constructed with a null class tag (due to using a deprecated method)" in {
    val csvStringMarshaller: ToByteStringMarshaller[String] =
      Marshaller.withFixedContentType(ContentTypes.`text/csv(UTF-8)`)(ByteString.apply)

    val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

    implicit val toResponseMarshaller: ToResponseMarshaller[Source[String, NotUsed]] =
      PredefinedToResponseMarshallers.fromEntityStreamingSupportAndByteStringMarshaller[String, NotUsed](jsonStreamingSupport, csvStringMarshaller)

    val route =
      get {
        // This is wrong since we try to render JSON, but String is not a valid top level element
        // we need to provide an explicit Marshaller[String, ByteString] if we really want to render a list of strings.
        val results = Source(List("One", "Two", "Three"))
        complete(results)
      }

    try {
      Get("/") ~> route ~> check {
        EventFilter.error(pattern = "None of the available marshallings ", occurrences = 1) intercept {
          responseAs[String] // should fail
        }
      }
    } catch {
      case ex: java.lang.RuntimeException if ex.getCause != null =>
        val cause = ex.getCause
        cause.getClass should ===(classOf[akka.http.scaladsl.marshalling.NoStrictlyCompatibleElementMarshallingAvailableException[_]])
        cause.getMessage should include("Please provide an implicit `Marshaller[T, HttpEntity]")
        cause.getMessage should include("that can render as [application/json]")
    }
  }
}
