/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import akka.testkit.AkkaSpec
import akka.http.scaladsl.model.headers.`User-Agent`
import com.typesafe.config.ConfigFactory

class ConnectionPoolSettingsSpec extends AkkaSpec {
  "ConnectionPoolSettings" should {
    "use akka.http.client settings by default" in {
      val settings = config(
        """
          akka.http.client.user-agent-header = "serva/0.0"
        """)

      settings.connectionSettings.userAgentHeader shouldEqual Some(`User-Agent`.parseFromValueString("serva/0.0").right.get)
    }
    "allow overriding client settings with akka.http.host-connection-pool.client" in {
      val settings = config(
        """
          akka.http.client.request-header-size-hint = 1024
          akka.http.client.user-agent-header = "serva/0.0"
          akka.http.host-connection-pool.client.user-agent-header = "serva/5.7"
        """)

      settings.connectionSettings.userAgentHeader shouldEqual Some(`User-Agent`.parseFromValueString("serva/5.7").right.get)
      settings.connectionSettings.requestHeaderSizeHint shouldEqual 1024 // still fall back
    }
    "allow max-open-requests = 1" in {
      config("akka.http.host-connection-pool.max-open-requests = 1").maxOpenRequests should be(1)
    }
    "allow max-open-requests = 42" in {
      config("akka.http.host-connection-pool.max-open-requests = 42").maxOpenRequests should be(42)
    }
    "allow per host overrides" in {

      val settingsString =
        """
          |akka.http.host-connection-pool {
          |  max-connections = 7
          |
          |  per-host-override : [
          |    {
          |      host-pattern = "akka.io"
          |      # can use same things as in global `host-connection-pool` section
          |      max-connections = 47
          |    },
          |   {
          |     host-pattern = "*.example.com"
          |     # allow `*` to apply overrides for all subdomains
          |     max-connections = 34
          |   },
          |   {
          |     host-pattern = "glob:*example2.com"
          |     max-connections = 39
          |   },
          |   {
          |     host-pattern = "regex:((w{3})?\\.)?scala-lang\\.(com|org)"
          |     max-connections = 36
          |   }
          |  ]
          |}
        """.stripMargin

      val settings = ConnectionPoolSettings(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader)))

      settings.forHost("akka.io").maxConnections shouldEqual 47
      settings.forHost("test.akka.io").maxConnections shouldEqual 7
      settings.forHost("example.com").maxConnections shouldEqual 34
      settings.forHost("www.example.com").maxConnections shouldEqual 34
      settings.forHost("example2.com").maxConnections shouldEqual 39
      settings.forHost("www.example2.com").maxConnections shouldEqual 39
      settings.forHost("www.someexample2.com").maxConnections shouldEqual 39
      settings.forHost("test.example.com").maxConnections shouldEqual 34
      settings.forHost("lightbend.com").maxConnections shouldEqual 7
      settings.forHost("www.scala-lang.org").maxConnections shouldEqual 36
      settings.forHost("scala-lang.org").maxConnections shouldEqual 36
      settings.forHost("ww.scala-lang.org").maxConnections shouldEqual 7
      settings.forHost("scala-lang.com").maxConnections shouldEqual 36
    }

    "allow overriding values from code" in {
      val settingsString =
        """
          |akka.http.host-connection-pool {
          |  max-connections = 7
          |
          |  per-host-override = [
          |    {
          |      host-pattern = "akka.io"
          |      # can use same things as in global `host-connection-pool` section
          |      max-connections = 47
          |    }
          |  ]
          |}
        """.stripMargin

      val settings = ConnectionPoolSettings(ConfigFactory.parseString(settingsString).withFallback(ConfigFactory.defaultReference(getClass.getClassLoader)))
      settings.forHost("akka.io").maxConnections shouldEqual 47
      settings.maxConnections shouldEqual 7

      val settingsWithCodeOverrides = settings.withMaxConnections(42)
      settingsWithCodeOverrides.forHost("akka.io").maxConnections shouldEqual 42
      settingsWithCodeOverrides.maxConnections shouldEqual 42
    }

    "choose the first matching override when there are multiple" in {
      val settingsString =
        """
          |akka.http.host-connection-pool {
          |  min-connections = 2
          |  max-connections = 7
          |
          |  per-host-override = [
          |    {
          |      host-pattern = "akka.io"
          |      # can use same things as in global `host-connection-pool` section
          |      max-connections = 27
          |    },
          |    {
          |      host-pattern = "*.io"
          |      # can use same things as in global `host-connection-pool` section
          |      min-connections = 22
          |      max-connections = 47
          |    }
          |  ]
          |}
        """.stripMargin

      val settings = ConnectionPoolSettings(ConfigFactory.parseString(settingsString).withFallback(ConfigFactory.defaultReference(getClass.getClassLoader)))
      settings.forHost("akka.io").maxConnections shouldEqual 27
      settings.forHost("other.io").maxConnections shouldEqual 47
      settings.forHost("akka.com").maxConnections shouldEqual 7
      settings.maxConnections shouldEqual 7

      // the '*.io' overrides are not selected, because akka.io occurs earlier:
      settings.forHost("akka.io").minConnections shouldEqual 2
      settings.forHost("other.io").minConnections shouldEqual 22
      settings.forHost("akka.com").minConnections shouldEqual 2
      settings.minConnections shouldEqual 2
    }

    def expectError(configString: String): String = Try(config(configString)) match {
      case Failure(cause) => cause.getMessage
      case Success(_)     => fail("Expected a failure when max-open-requests is not a power of 2")
    }
  }

  def config(configString: String): ConnectionPoolSettings =
    ConnectionPoolSettings(configString)
}
