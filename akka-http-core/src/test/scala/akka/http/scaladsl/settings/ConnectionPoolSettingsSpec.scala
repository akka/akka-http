/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
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
    "produce a nice error message when max-open-requests" in {
      expectError("akka.http.host-connection-pool.max-open-requests = 10") should include("Perhaps try 8 or 16")
      expectError("akka.http.host-connection-pool.max-open-requests = 100") should include("Perhaps try 64 or 128")
      expectError("akka.http.host-connection-pool.max-open-requests = 1000") should include("Perhaps try 512 or 1024")
    }

    "allow per host overrides" in {

      val settingsString =
        """
          |akka.http.host-connection-pool {
          |  max-connections = 7
          |
          |  per-host-override : [
          |    {
          |      "akka.io" : { # can use same things as in global `host-connection-pool` section
          |        max-connections = 47
          |      }
          |    },
          |
          |   {
          |     "*.example.com" : { # allow `*` to apply overrides for all subdomains
          |       max-connections = 34
          |     }
          |   },
          |
          |   {
          |     "glob:*example2.com" : {
          |       max-connections = 39
          |     }
          |   },
          |
          |   {
          |     "regex:((w{3})?\\.)?scala-lang\\.(com|org)" : {
          |       max-connections = 36
          |     }
          |   }
          |  ]
          |}
        """.stripMargin

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("akka.io").maxConnections shouldEqual 47

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("test.akka.io").maxConnections shouldEqual 7

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("example.com").maxConnections shouldEqual 34

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("www.example.com").maxConnections shouldEqual 34

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("example2.com").maxConnections shouldEqual 39

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("www.example2.com").maxConnections shouldEqual 39

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("www.someexample2.com").maxConnections shouldEqual 39

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("test.example.com").maxConnections shouldEqual 34

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("lightbend.com").maxConnections shouldEqual 7

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("www.scala-lang.org").maxConnections shouldEqual 36

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("scala-lang.org").maxConnections shouldEqual 36

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("ww.scala-lang.org").maxConnections shouldEqual 7

      ConnectionPoolSettings.withOverrides(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("scala-lang.com").maxConnections shouldEqual 36

      // Make sure calls to .apply instead of .forDefault don't get overridden by config
      ConnectionPoolSettings(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("akka.io").maxConnections shouldEqual 7

    }

    "disregard per host overrides when not created via forDefault method" in {
      val settingsString =
        """
          |akka.http.host-connection-pool {
          |  max-connections = 7
          |
          |  per-host-override : [
          |    {
          |      "akka.io" = { # can use same things as in global `host-connection-pool` section
          |        max-connections = 47
          |      }
          |    }
          |  ]
          |}
        """.stripMargin

      ConnectionPoolSettings(
        ConfigFactory.parseString(settingsString)
          .withFallback(ConfigFactory.defaultReference(getClass.getClassLoader))).forHost("akka.io").maxConnections shouldEqual 7

    }

    def expectError(configString: String): String = Try(config(configString)) match {
      case Failure(cause) => cause.getMessage
      case Success(_)     => fail("Expected a failure when max-open-requests is not a power of 2")
    }
  }

  def config(configString: String): ConnectionPoolSettings =
    ConnectionPoolSettings(configString)
}
