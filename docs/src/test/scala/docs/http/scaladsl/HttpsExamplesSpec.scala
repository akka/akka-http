/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import com.github.ghik.silencer.silent
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import docs.CompileOnlySpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

// TODO https://github.com/akka/akka-http/issues/2845
@silent("deprecated")
class HttpsExamplesSpec extends AnyWordSpec with Matchers with CompileOnlySpec {

  "disable SNI for connection" in compileOnlySpec {
    val unsafeHost = "example.com"
    //#disable-sni-connection
    implicit val system = ActorSystem()

    // WARNING: disabling SNI is a very bad idea, please don't unless you have a very good reason to.
    val badSslConfig = AkkaSSLConfig().mapSettings(s => s.withLoose(s.loose.withDisableSNI(true)))
    val badCtx = Http().createClientHttpsContext(badSslConfig)
    Http().outgoingConnectionHttps(unsafeHost, connectionContext = badCtx)
    //#disable-sni-connection
  }
}
