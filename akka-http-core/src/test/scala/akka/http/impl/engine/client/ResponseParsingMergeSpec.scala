/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.http.impl.engine.client.OutgoingConnectionBlueprint.ResponseParsingMerge
import akka.http.impl.engine.parsing.HttpResponseParser.ResponseContext
import akka.http.impl.engine.parsing.ParserOutput.EntityStreamError
import akka.http.impl.engine.parsing.{ HttpHeaderParser, HttpResponseParser, ParserOutput }
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ParserSettings
import akka.stream.TLSProtocol.SessionBytes
import akka.stream.scaladsl.{ GraphDSL, RunnableGraph, Sink, Source }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.stream.{ ActorMaterializer, Attributes, ClosedShape }
import akka.testkit.AkkaSpec
import akka.util.ByteString

class ResponseParsingMergeSpec extends AkkaSpec {

  val parserSettings = ParserSettings(system)

  "The ResponseParsingMerge stage" should {

    "not lose entity truncation errors on upstream finish" in {
      implicit val mat: ActorMaterializer = ActorMaterializer()

      val inBypassProbe = TestPublisher.manualProbe[OutgoingConnectionBlueprint.BypassData]()
      val inSessionBytesProbe = TestPublisher.manualProbe[SessionBytes]()
      val responseProbe = TestSubscriber.manualProbe[List[ParserOutput.ResponseOutput]]()

      val responseParsingMerge: ResponseParsingMerge = {
        val rootParser = new HttpResponseParser(parserSettings, HttpHeaderParser(parserSettings, log))
        new ResponseParsingMerge(rootParser)
      }

      RunnableGraph.fromGraph(
        GraphDSL.create() { implicit b =>
          import GraphDSL.Implicits._
          val parsingMerge = b.add(responseParsingMerge)

          Source.fromPublisher(inBypassProbe) ~> parsingMerge.in1
          Source.fromPublisher(inSessionBytesProbe) ~> parsingMerge.in0
          parsingMerge.out ~> Sink.fromSubscriber(responseProbe)

          ClosedShape
        }.withAttributes(Attributes.inputBuffer(1, 8))
      ).run()

      val inSessionBytesSub = inSessionBytesProbe.expectSubscription()
      val inBypassSub = inBypassProbe.expectSubscription()
      val responseSub = responseProbe.expectSubscription()

      responseSub.request(1)
      inSessionBytesSub.expectRequest()
      inBypassSub.sendNext(ResponseContext(HttpMethods.GET, None))

      inSessionBytesSub.sendNext(SessionBytes(null, ByteString(
        """HTTP/1.1 200 OK
          |Transfer-Encoding: chunked
          |Connection: lalelu
          |Content-Type: application/pdf
          |Server: spray-can
          |
          |1
          |0
          |2
          |01
          |3
          |012
          |4
          |0123
          |5
          |01234""".stripMargin
      )))

      inSessionBytesSub.sendNext(SessionBytes(null, ByteString(
        """
          |6
          |012345
          |7
          |0123456
          |8
          |01234567
          |9
          |012345678""".stripMargin
      )))

      inSessionBytesSub.sendComplete()

      responseSub.request(2)
      val responseChunks = responseProbe.expectNextN(3).flatten
      responseProbe.expectComplete()

      responseChunks.last shouldBe an[EntityStreamError]
      responseChunks.last shouldEqual EntityStreamError(ErrorInfo("Entity stream truncation. The HTTP parser was receiving an entity when the underlying connection was closed unexpectedly."))
    }

  }

}
