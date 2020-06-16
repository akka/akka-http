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

  "disable hostname verification for connection" in compileOnlySpec {
    val unsafeHost = "example.com"
    //#disable-hostname-verification-connection
    implicit val system = ActorSystem()

    // WARNING: disabling host name verification is a very bad idea, please don't unless you have a very good reason to.
    val badSslConfig = AkkaSSLConfig().mapSettings(s => s.withLoose(s.loose.withDisableHostnameVerification(true)))
    val badCtx = Http().createClientHttpsContext(badSslConfig)
    Http().outgoingConnectionHttps(unsafeHost, connectionContext = badCtx)
    //#disable-hostname-verification-connection
  }
}
