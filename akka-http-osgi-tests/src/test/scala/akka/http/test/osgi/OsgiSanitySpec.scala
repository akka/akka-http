/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.test.osgi

import java.io.File

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.osgi.OsgiActorSystemFactory
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import javax.inject.Inject
import org.junit.Assert._
import org.junit.Test
import org.junit.runner.RunWith
import org.ops4j.pax.exam.CoreOptions._
import org.ops4j.pax.exam.junit.PaxExam
import org.ops4j.pax.exam.{Configuration, Option ⇒ PaxOption}
import org.osgi.framework.BundleContext
import org.scalatest.junit.JUnitSuite
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object OsgiSanitySpec {
  val Log: Logger = LoggerFactory.getLogger(classOf[OsgiSanitySpec])

  /** The directory with the bundles to be deployed. */
  val BundlesDir = new File("target/dependencies")

  /** The path of the test server started by the test driver. */
  val ServerPath = "testServer"

  /**
    * Name of a system property that defines the port the test server is
    * listening on.
    */
  val PropServerPort = "osgiSanitySpec.serverPort"

  /** Timeout when waiting for results. */
  val Timeout: FiniteDuration = 10.seconds

  /**
    * Returns an array with options for the bundles to be deployed in the
    * test OSGi container.
    *
    * @return an array with bundle options
    */
  def bundlesOption(): Array[PaxOption] =
    BundlesDir.listFiles().map(f ⇒ bundle(f.toURI.toString))
}

/**
  * PaxExam test class to check whether akka-http and all of its dependencies
  * are functional in an OSGi environment.
  */
@RunWith(classOf[PaxExam])
class OsgiSanitySpec extends JUnitSuite {

  import OsgiSanitySpec._

  @Inject private var bundleContext: BundleContext = _

  @Configuration
  def config(): Array[PaxOption] = {
    Array(junitBundles(), composite(bundlesOption(): _*))
  }

  @Test def testHttpInteraction(): Unit = {
    Log.info("Running test.")
    implicit val system: ActorSystem = createActorSystem
    implicit val ec: ExecutionContext = system.dispatcher
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    val TestPath = "osgi"
    val route = path(TestPath) {
      get {
        complete(HttpResponse(entity = "successful"))
      }
    }
    val bindingFuture = Http().bindAndHandle(route, "localhost", 0)
    val resultFuture = bindingFuture
      .map(b ⇒ s"http://localhost:${b.localAddress.getPort}/$TestPath")
      .flatMap(url ⇒ Http().singleRequest(HttpRequest(uri = url)))
      .flatMap(readResponse)

    val response = Await.result(resultFuture, Timeout)
    assertEquals("Wrong response", "successful",
      response.utf8String)

    val termFuture = bindingFuture.flatMap(f ⇒ f.unbind()).map(_ ⇒ system.terminate())
    Await.result(termFuture, Timeout)
  }

  /**
    * Creates an actor system in this OSGi environment.
    *
    * @return the newly created actor system
    */
  private def createActorSystem: ActorSystem = {
    val factory = OsgiActorSystemFactory(bundleContext, ConfigFactory.empty())
    factory.createActorSystem("httpTestSystem")
  }

  /**
    * Extracts the entity from a response as ByteString.
    *
    * @param resp the response
    * @param mat  the materializer
    * @return the ByteString read from the response's entity
    */
  private def readResponse(resp: HttpResponse)(implicit mat: ActorMaterializer)
  : Future[ByteString] =
    resp.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)
}
