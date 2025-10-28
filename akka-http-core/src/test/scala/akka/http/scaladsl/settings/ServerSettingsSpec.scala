/*
 * Copyright (C) 2020-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.settings

import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

class ServerSettingsSpec extends AkkaSpec {
  "ServerSettings" should {
    "fail early when creating ServerSettings with incomplete ParserSettings" in {
      // This creates 'generic' parserSettings, without server-specific (or client-specific) values.
      val parserSettings = ParserSettings(system)

      // This includes ParserSettings, complete with server-specific settings
      val serverSettings = ServerSettings(system)

      // This would create 'defective' serverSettings, since these
      // parserSettings don't contain the server or client-specific values. Notably,
      // max-content-length would not be set (and throw an exception on access).
      val e = intercept[IllegalArgumentException] {
        serverSettings.withParserSettings(parserSettings)
      }
      e.getMessage should include("does not contain the server-specific settings")
    }

    "use old preview setting value by default" in {
      {
        val serverSettings = ServerSettings(system)
        serverSettings.http2Enabled should ===(false)
      }

      {
        val serverSettings = ServerSettings(ConfigFactory.load("http2-preview-fallback.conf"))
        serverSettings.http2Enabled should ===(true)
      }
    }
  }
}
