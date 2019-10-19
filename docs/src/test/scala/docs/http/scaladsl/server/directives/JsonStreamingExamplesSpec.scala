/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.NotUsed
import akka.http.scaladsl.common.{ EntityStreamingSupport, JsonEntityStreamingSupport }
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.RoutingSpec
import akka.http.scaladsl.server.{ UnacceptedResponseContentTypeRejection, UnsupportedRequestContentTypeRejection }
import akka.stream.scaladsl.{ Flow, Source }
import akka.util.ByteString
import docs.CompileOnlySpec

import scala.concurrent.Future

class JsonStreamingExamplesSpec extends RoutingSpec with CompileOnlySpec {

  //#tweet-model
  case class Tweet(uid: Int, txt: String)
  //#tweet-model

  val tweets = List(
    Tweet(1, "#Akka rocks!"),
    Tweet(2, "Streaming is so hot right now!"),
    Tweet(3, "You cannot enter the same river twice."))
  def getTweets = Source(tweets)

  //#tweet-format
  object MyTweetJsonProtocol
    extends akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
    with spray.json.DefaultJsonProtocol {

    implicit val tweetFormat = jsonFormat2(Tweet.apply)
  }
  //#tweet-format

  "spray-json-response-streaming" in {
    //#spray-json-response-streaming
    // [1] import "my protocol", for marshalling Tweet objects:
    import MyTweetJsonProtocol._

    // [2] pick a Source rendering support trait:
    // Note that the default support renders the Source as JSON Array
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
    //#spray-json-response-streaming
  }

  "line-by-line-json-response-streaming" in {
    //#line-by-line-json-response-streaming
    import MyTweetJsonProtocol._

    // Configure the EntityStreamingSupport to render the elements as:
    // {"example":42}
    // {"example":43}
    // ...
    // {"example":1000}
    val newline = ByteString("\n")

    implicit val jsonStreamingSupport = EntityStreamingSupport.json()
      .withFramingRenderer(Flow[ByteString].map(bs => bs ++ newline))

    val route =
      path("tweets") {
        // [3] simply complete a request with a source of tweets:
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
    //#line-by-line-json-response-streaming
  }

  "csv-example" in {
    //#csv-example
    // [1] provide a marshaller to ByteString
    implicit val tweetAsCsv = Marshaller.strict[Tweet, ByteString] { t =>
      Marshalling.WithFixedContentType(ContentTypes.`text/csv(UTF-8)`, () => {
        val txt = t.txt.replaceAll(",", ".")
        val uid = t.uid
        ByteString(List(uid, txt).mkString(","))
      })
    }

    // [2] enable csv streaming:
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
    //#csv-example
  }

  "response-streaming-modes" in {
    {
      //#async-rendering
      import MyTweetJsonProtocol._
      implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
        EntityStreamingSupport.json()
          .withParallelMarshalling(parallelism = 8, unordered = false)

      path("tweets") {
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }
      //#async-rendering
    }

    {
      //#async-unordered-rendering
      import MyTweetJsonProtocol._
      implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
        EntityStreamingSupport.json()
          .withParallelMarshalling(parallelism = 8, unordered = true)

      path("tweets" / "unordered") {
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }
      //#async-unordered-rendering
    }
  }

  //#measurement-model
  case class Measurement(id: String, value: Int)

  //#measurement-model

  //#measurement-format
  object MyMeasurementJsonProtocol
    extends akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
    with spray.json.DefaultJsonProtocol {

    implicit val measurementFormat = jsonFormat2(Measurement.apply)
  }
  //#measurement-format

  "spray-json-request-streaming" in {
    //#spray-json-request-streaming
    // [1] import "my protocol", for unmarshalling Measurement objects:
    import MyMeasurementJsonProtocol._

    // [2] enable Json Streaming
    implicit val jsonStreamingSupport = EntityStreamingSupport.json()

    // prepare your persisting logic here
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

}
