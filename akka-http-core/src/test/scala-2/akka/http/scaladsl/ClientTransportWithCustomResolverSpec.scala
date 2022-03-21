/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import java.util.concurrent.TimeoutException

import akka.Done
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings }
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.testkit._
import org.scalatest.OptionValues

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

class ClientTransportWithCustomResolverSpec extends AkkaSpecWithMaterializer("akka.http.server.request-timeout = infinite") with OptionValues {
  "A custom resolver" should {

    "change to the desired destination" in {
      val hostnameToFind = "some-name-out-there"
      val portToFind = 21345
      val bindingFuture = Http().newServerAt("localhost", 0).bindSync(_ => HttpResponse())
      val binding = Await.result(bindingFuture, 3.seconds.dilated)

      val otherHostAndPortTransport: ClientTransport = ClientTransport.withCustomResolver((host, port) => {
        host shouldBe hostnameToFind
        port shouldBe portToFind
        Future.successful(binding.localAddress)
      })

      val customResolverPool =
        ConnectionPoolSettings(system)
          .withConnectionSettings(ClientConnectionSettings(system).withTransport(otherHostAndPortTransport))

      val respFuture = Http().singleRequest(HttpRequest(POST, s"http://$hostnameToFind:$portToFind/"), settings = customResolverPool)
      val resp = Await.result(respFuture, 3.seconds.dilated)
      resp.status shouldBe StatusCodes.OK

      Await.ready(binding.unbind(), 1.second.dilated)
    }

    "resolve not before a connection is needed" in {
      val hostnameToFind = "some-name-out-there"
      val portToFind = 21345
      val bindingFuture = Http().newServerAt("localhost", 0).bindSync(_ => HttpResponse())
      val binding = Await.result(bindingFuture, 3.seconds.dilated)

      val resolverCalled = Promise[Done]()

      val otherHostAndPortTransport: ClientTransport = ClientTransport.withCustomResolver((host, port) => {
        host shouldBe hostnameToFind
        port shouldBe portToFind
        resolverCalled.success(Done)
        Future.successful(binding.localAddress)
      })

      val customResolverPool =
        ConnectionPoolSettings(system)
          .withConnectionSettings(ClientConnectionSettings(system).withTransport(otherHostAndPortTransport))

      val (sourceQueue, sinkQueue) =
        Source.queue[(HttpRequest, Unit)](1, OverflowStrategy.backpressure)
          .via(Http().superPool[Unit](settings = customResolverPool))
          .toMat(Sink.queue())(Keep.both)
          .run()

      // nothing happens for at least 3 seconds
      assertThrows[TimeoutException](Await.result(resolverCalled.future, 3.seconds.dilated))

      // resolving kicks in when a request comes along
      sourceQueue.offer(HttpRequest(POST, s"http://$hostnameToFind:$portToFind/") -> ())
      Await.result(resolverCalled.future, 3.seconds.dilated) shouldBe Done
      val resp = Await.result(sinkQueue.pull(), 3.seconds.dilated).value._1.get
      resp.status shouldBe StatusCodes.OK

      Await.ready(binding.unbind(), 1.second.dilated)
    }
  }
}
