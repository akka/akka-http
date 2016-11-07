/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.http.management

import java.io.InputStream
import java.security.{ KeyStore, SecureRandom }
import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManagerFactory }

import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.cluster.InternalClusterAction.LeaderActionsTick
import akka.cluster._
import akka.http.scaladsl.{ ConnectionContext, Http, HttpsConnectionContext }
import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
import akka.http.scaladsl.model.{ ContentTypes, HttpRequest, StatusCodes }
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpecLike }

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class ClusterHttpManagementSpec extends WordSpecLike with Matchers
  with ClusterHttpManagementJsonProtocol with ClusterHttpManagementHelper {

  val config = ConfigFactory.parseString(
    """
      |akka.cluster {
      |  auto-down-unreachable-after = 0s
      |  periodic-tasks-initial-delay = 120 seconds // turn off scheduled tasks
      |  publish-stats-interval = 0 s # always, when it happens
      |  failure-detector.implementation-class = akka.cluster.FailureDetectorPuppet
      |}
      |akka.actor.provider = "cluster"
      |akka.remote.log-remote-lifecycle-events = off
      |akka.remote.netty.tcp.port = 0
      |#akka.loglevel = DEBUG
    """.stripMargin)

  "Http Cluster Management" should {
    "start and stop" when {
      "not setting any security" in {
        val configClusterHttpManager = ConfigFactory.parseString(
          """
            |akka.http.cluster.management.hostname = "127.0.0.1"
            |akka.http.cluster.management.port = 19999
          """.stripMargin)

        implicit val system = ActorSystem("test", config.withFallback(configClusterHttpManager))
        import system.dispatcher
        val cluster = Cluster(system)

        val selfAddress = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        cluster.join(selfAddress)
        cluster.clusterCore ! LeaderActionsTick

        val clusterHttpManagement = ClusterHttpManagement(cluster)
        clusterHttpManagement.start()

        implicit val materializer = ActorMaterializer()

        val responseGetMembersFuture = Http().singleRequest(HttpRequest(uri = "http://127.0.0.1:19999/members"))
        val responseGetMembers = Await.result(responseGetMembersFuture, 5 seconds)

        Unmarshal(responseGetMembers.entity).to[ClusterMembers].onSuccess {
          case clusterMembers: ClusterMembers ⇒
            memberToClusterMember(cluster.readView.members.head) shouldEqual clusterMembers.members.head
        }

        responseGetMembers.entity.getContentType shouldEqual ContentTypes.`application/json`
        responseGetMembers.status shouldEqual StatusCodes.OK

        val bindingFuture = clusterHttpManagement.stop()
        Await.ready(bindingFuture, 5 seconds)
        system.terminate()
      }

      "setting basic authentication" in {
        val configClusterHttpManager = ConfigFactory.parseString(
          """
            |akka.http.cluster.management.hostname = "127.0.0.1"
            |akka.http.cluster.management.port = 20000
          """.stripMargin)

        implicit val system = ActorSystem("test", config.withFallback(configClusterHttpManager))
        import system.dispatcher
        val cluster = Cluster(system)

        val selfAddress = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        cluster.join(selfAddress)
        cluster.clusterCore ! LeaderActionsTick

        def myUserPassAuthenticator(credentials: Credentials): Future[Option[String]] =
          credentials match {
            case p @ Credentials.Provided(id) ⇒
              Future {
                // potentially
                if (p.verify("p4ssw0rd")) Some(id)
                else None
              }
            case _ ⇒ Future.successful(None)
          }

        val clusterHttpManagement = ClusterHttpManagement(cluster, myUserPassAuthenticator(_))
        clusterHttpManagement.start()

        implicit val materializer = ActorMaterializer()

        val httpRequest = HttpRequest(uri = "http://127.0.0.1:20000/members")
          .addHeader(Authorization(BasicHttpCredentials("user", "p4ssw0rd")))
        val responseGetMembersFuture = Http().singleRequest(httpRequest)
        val responseGetMembers = Await.result(responseGetMembersFuture, 5 seconds)

        Unmarshal(responseGetMembers.entity).to[ClusterMembers].onSuccess {
          case clusterMembers: ClusterMembers ⇒
            memberToClusterMember(cluster.readView.members.head) shouldEqual clusterMembers.members.head
        }
        responseGetMembers.entity.getContentType shouldEqual ContentTypes.`application/json`
        responseGetMembers.status shouldEqual StatusCodes.OK

        val bindingFuture = clusterHttpManagement.stop()
        Await.ready(bindingFuture, 5 seconds)
        system.terminate()
      }

      "setting ssl" in {
        val configClusterHttpManager = ConfigFactory.parseString(
          """
            |akka.http.cluster.management.hostname = "127.0.0.1"
            |akka.http.cluster.management.port = 20001
            |
            |akka.ssl-config {
            |  loose {
            |    disableSNI = true
            |    disableHostnameVerification = true
            |  }
            |}
          """.stripMargin)

        implicit val system = ActorSystem("test", config.withFallback(configClusterHttpManager))
        import system.dispatcher
        val cluster = Cluster(system)

        val selfAddress = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        cluster.join(selfAddress)
        cluster.clusterCore ! LeaderActionsTick

        val password: Array[Char] = "password".toCharArray // do not store passwords in code, read them from somewhere safe!

        val ks: KeyStore = KeyStore.getInstance("PKCS12")
        val keystore: InputStream = getClass.getClassLoader.getResourceAsStream("httpsDemoKeys/keys/keystore.p12")

        require(keystore != null, "Keystore required!")
        ks.load(keystore, password)

        val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
        keyManagerFactory.init(ks, password)

        val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
        tmf.init(ks)

        val sslContext: SSLContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
        val https: HttpsConnectionContext = ConnectionContext.https(sslContext)

        val clusterHttpManagement = ClusterHttpManagement(cluster, https)
        clusterHttpManagement.start()

        implicit val materializer = ActorMaterializer()

        val httpRequest = HttpRequest(uri = "https://127.0.0.1:20001/members")
        val responseGetMembersFuture = Http().singleRequest(httpRequest, connectionContext = https)
        val responseGetMembers = Await.result(responseGetMembersFuture, 5 seconds)

        Unmarshal(responseGetMembers.entity).to[ClusterMembers].onSuccess {
          case clusterMembers: ClusterMembers ⇒
            memberToClusterMember(cluster.readView.members.head) shouldEqual clusterMembers.members.head
        }
        responseGetMembers.entity.getContentType shouldEqual ContentTypes.`application/json`
        responseGetMembers.status shouldEqual StatusCodes.OK

        val bindingFuture = clusterHttpManagement.stop()
        Await.ready(bindingFuture, 5 seconds)
        system.terminate()
      }
    }
  }
}
