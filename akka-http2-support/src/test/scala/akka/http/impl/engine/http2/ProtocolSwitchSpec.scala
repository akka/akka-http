/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import scala.concurrent.Promise

import akka.Done
import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.TLSProtocol._
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.TLSPlacebo
import akka.util.ByteString

import akka.testkit.AkkaSpec
import org.scalatest.exceptions.TestFailedException
import org.scalatest.time.{ Span, Seconds, Milliseconds }

class ProtocolSwitchSpec extends AkkaSpec {
  implicit val mat = ActorMaterializer()

  override implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(2, Seconds), interval = Span(50, Milliseconds))

  "The ProtocolSwitch" should {
    "switch to http2 when the connection preface arrives separately from the payload" in {
      val payload = ByteString("dfadfasdfa")
      val http1flowMaterialized = Promise[Done]()
      val http2flowMaterialized = Promise[Done]()
      val (in, out) = Source.queue(100, OverflowStrategy.fail)
        .viaMat(ProtocolSwitch.byPreface(
          Flow[SslTlsInbound]
            .collect { case SessionBytes(_, bytes) => SendBytes(bytes) }
            .mapMaterializedValue(_ => { http1flowMaterialized.success(Done); NotUsed }),
          Flow[SslTlsInbound]
            .collect { case SessionBytes(_, bytes) => SendBytes(bytes) }
            .mapMaterializedValue(_ => { http2flowMaterialized.success(Done); NotUsed })
        ))(Keep.left)
        .toMat(Sink.queue())(Keep.both)
        .run()

      in.offer(SessionBytes(TLSPlacebo.dummySession, Http2Protocol.ClientConnectionPreface)).futureValue should be(Enqueued)
      in.offer(SessionBytes(TLSPlacebo.dummySession, payload)).futureValue should be(Enqueued)

      assertThrows[TestFailedException] {
        http1flowMaterialized.future.futureValue
      }
      http2flowMaterialized.future.futureValue should be(Done)
      out.pull.futureValue should be(Some(SendBytes(Http2Protocol.ClientConnectionPreface)))
      out.pull.futureValue should be(Some(SendBytes(payload)))
    }

    "switch to http2 when the connection preface arrives together with the payload" in {
      val payload = ByteString("dfadfasdfa")
      val http1flowMaterialized = Promise[Done]()
      val http2flowMaterialized = Promise[Done]()

      val (in, out) = Source.queue(100, OverflowStrategy.fail)
        .viaMat(ProtocolSwitch.byPreface(
          Flow[SslTlsInbound]
            .collect { case SessionBytes(_, bytes) => SendBytes(bytes) }
            .mapMaterializedValue(_ => { http1flowMaterialized.success(Done); NotUsed }),
          Flow[SslTlsInbound]
            .collect { case SessionBytes(_, bytes) => SendBytes(bytes) }
            .mapMaterializedValue(_ => { http2flowMaterialized.success(Done); NotUsed })
        ))(Keep.left)
        .toMat(Sink.queue())(Keep.both)
        .run()

      in.offer(SessionBytes(TLSPlacebo.dummySession, Http2Protocol.ClientConnectionPreface ++ payload)).futureValue should be(Enqueued)

      assertThrows[TestFailedException] {
        http1flowMaterialized.future.futureValue
      }
      http2flowMaterialized.future.futureValue should be(Done)
      out.pull.futureValue should be(Some(SendBytes(Http2Protocol.ClientConnectionPreface ++ payload)))
    }

    "switch to http2 when the connection preface arrives in two parts" ignore {
      val payload = ByteString("dfadfasdfa")
      val http1flowMaterialized = Promise[Done]()
      val http2flowMaterialized = Promise[Done]()

      val (in, out) = Source.queue(100, OverflowStrategy.fail)
        .viaMat(ProtocolSwitch.byPreface(
          Flow[SslTlsInbound]
            .collect { case SessionBytes(_, bytes) => SendBytes(bytes) }
            .mapMaterializedValue(_ => { http1flowMaterialized.success(Done); NotUsed }),
          Flow[SslTlsInbound]
            .collect { case SessionBytes(_, bytes) => SendBytes(bytes) }
            .mapMaterializedValue(_ => { http2flowMaterialized.success(Done); NotUsed })))(Keep.left)
        .toMat(Sink.queue())(Keep.both)
        .run()

      in.offer(SessionBytes(TLSPlacebo.dummySession, Http2Protocol.ClientConnectionPreface.take(15))).futureValue should be(Enqueued)
      in.offer(SessionBytes(TLSPlacebo.dummySession, Http2Protocol.ClientConnectionPreface.drop(15))).futureValue should be(Enqueued)
      in.offer(SessionBytes(TLSPlacebo.dummySession, payload)).futureValue should be(Enqueued)

      assertThrows[TestFailedException] {
        http1flowMaterialized.future.futureValue
      }
      http2flowMaterialized.future.futureValue should be(Done)
      out.pull.futureValue should be(Some(SendBytes(Http2Protocol.ClientConnectionPreface)))
      out.pull.futureValue should be(Some(SendBytes(payload)))
    }

    "select http1 when receiving a short http1 request" in {
      val payload = ByteString("GET / HTTP/1.0\n\n")
      val http1flowMaterialized = Promise[Done]()
      val http2flowMaterialized = Promise[Done]()

      val (in, out) = Source.queue(100, OverflowStrategy.fail)
        .viaMat(ProtocolSwitch.byPreface(
          Flow[SslTlsInbound]
            .collect { case SessionBytes(_, bytes) => SendBytes(bytes) }
            .mapMaterializedValue(_ => { http1flowMaterialized.success(Done); NotUsed }),
          Flow[SslTlsInbound]
            .collect { case SessionBytes(_, bytes) => SendBytes(bytes) }
            .mapMaterializedValue(_ => { http2flowMaterialized.success(Done); NotUsed })))(Keep.left)
        .toMat(Sink.queue())(Keep.both)
        .run()

      in.offer(SessionBytes(TLSPlacebo.dummySession, payload)).futureValue should be(Enqueued)

      assertThrows[TestFailedException] {
        http2flowMaterialized.future.futureValue
      }
      http1flowMaterialized.future.futureValue should be(Done)
      out.pull.futureValue should be(Some(SendBytes(payload)))
    }
  }
}
