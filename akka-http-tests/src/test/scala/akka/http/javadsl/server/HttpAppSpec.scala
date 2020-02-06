/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.net.SocketException

import akka.Done
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.javadsl.ServerBinding
import akka.http.javadsl.settings.ServerSettings
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import akka.testkit.EventFilter
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class HttpAppSpec extends AkkaSpecWithMaterializer with RequestBuilding with Eventually {
  import system.dispatcher

  def withMinimal(testCode: MinimalHttpApp => Any): Unit = {
    val minimal = new MinimalHttpApp()
    try testCode(minimal)
    finally {
      if (!minimal.shutdownTrigger.isDone) minimal.shutdownTrigger.complete(Done)
    }
  }

  def withSneaky(testCode: SneakHttpApp => Any): Unit = {
    val sneaky = new SneakHttpApp()
    try testCode(sneaky)
    finally {
      if (!sneaky.shutdownTrigger.isDone) sneaky.shutdownTrigger.complete(Done)
    }
  }

  "HttpApp Java" should {

    "start only with host and port" in withMinimal { minimal =>

      val server = Future {
        minimal.startServer("localhost", 0)
      }

      val binding = minimal.bindingPromise.get(5, TimeUnit.SECONDS)

      // Checking server is up and running
      callAndVerify(binding, "foo")

      // Requesting the server to shutdown
      minimal.shutdown()
      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)

    }

    "start without ActorSystem" in withMinimal { minimal =>

      val server = Future {
        minimal.startServer("localhost", 0, ServerSettings.create(ConfigFactory.load))
      }

      val binding = minimal.bindingPromise.get(5, TimeUnit.SECONDS)

      // Checking server is up and running
      callAndVerify(binding, "foo")

      // Requesting the server to shutdown
      minimal.shutdown()

      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)

    }

    "start providing an ActorSystem" in withMinimal { minimal =>

      val server = Future {
        minimal.startServer("localhost", 0, system)
      }

      val binding = minimal.bindingPromise.get(5, TimeUnit.SECONDS)

      // Checking server is up and running
      callAndVerify(binding, "foo")

      // Requesting the server to shutdown
      minimal.shutdown()

      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)
      system.whenTerminated.isCompleted should ===(false)

    }

    "start providing an ActorSystem and Settings" in withMinimal { minimal =>

      val server = Future {
        minimal.startServer("localhost", 0, ServerSettings.create(system), system)
      }

      val binding = minimal.bindingPromise.get(5, TimeUnit.SECONDS)

      // Checking server is up and running
      callAndVerify(binding, "foo")

      // Requesting the server to shutdown
      minimal.shutdown()

      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)
      system.whenTerminated.isCompleted should ===(false)

    }

    "provide binding if available" in withMinimal { minimal =>

      intercept[IllegalStateException] {
        minimal.binding()
      }

      val server = Future {
        minimal.startServer("127.0.0.1", 0, ServerSettings.create(ConfigFactory.load))
      }

      val binding = minimal.bindingPromise.get(5, TimeUnit.SECONDS)

      minimal.binding().localAddress.getAddress.getHostAddress should ===("127.0.0.1")

      // Checking server is up and running
      callAndVerify(binding, "foo")

      // Requesting the server to shutdown
      minimal.shutdown()
      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)

    }

    "notify" when {

      "shutting down" in withSneaky { sneaky =>

        val server = Future {
          sneaky.startServer("localhost", 0, ServerSettings.create(ConfigFactory.load))
        }

        sneaky.postServerShutdownCalled.get() should ===(false)

        val binding = sneaky.bindingPromise.get(5, TimeUnit.SECONDS)

        // Checking server is up and running
        callAndVerify(binding, "foo")

        // Requesting the server to shutdown
        sneaky.shutdown()
        Await.ready(server, Duration(1, TimeUnit.SECONDS))
        server.isCompleted should ===(true)
        eventually {
          sneaky.postServerShutdownCalled.get() should ===(true)
        }

      }

      "after binding is successful" in withSneaky { sneaky =>

        val server = Future {
          sneaky.startServer("localhost", 0, ServerSettings.create(ConfigFactory.load))
        }

        val binding = sneaky.bindingPromise.get(5, TimeUnit.SECONDS)

        sneaky.postBindingCalled.get() should ===(true)

        // Checking server is up and running
        callAndVerify(binding, "foo")

        // Requesting the server to shutdown
        sneaky.shutdown()
        Await.ready(server, Duration(1, TimeUnit.SECONDS))
        server.isCompleted should ===(true)

      }

      "after binding is unsuccessful" in withSneaky { sneaky =>
        val serverSocket = new ServerSocket()
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0))
        val port = serverSocket.getLocalPort

        try {
          EventFilter[SocketException](pattern = ".*Address already in use.*", occurrences = 1) intercept {
            sneaky.startServer("localhost", port, system)
          }

          eventually {
            sneaky.postBindingFailureCalled.get() should ===(true)
          }
        } finally serverSocket.close()
      }

    }

  }

  private def callAndVerify(binding: ServerBinding, path: String) = {

    val host = binding.localAddress.getHostString
    val port = binding.localAddress.getPort

    val request = HttpRequest(uri = s"http://$host:$port/$path")
    val response = Http().singleRequest(request)
    response.futureValue.status should ===(StatusCodes.OK)
  }

}
