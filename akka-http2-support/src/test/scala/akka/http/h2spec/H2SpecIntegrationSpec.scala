/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.h2spec

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import akka.http.impl.util.{ ExampleHttpContexts, WithLogCapturing }
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, HttpResponse }
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.Http2
import akka.stream.ActorMaterializer
import akka.testkit._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._
import scala.sys.process._

class H2SpecIntegrationSpec extends AkkaSpec(
  """
     akka {
       loglevel = DEBUG
       loggers = ["akka.http.impl.util.SilenceAllTestEventListener"]
       http.server.log-unencrypted-network-bytes = off
       http.server.http2.log-frames = on

       actor.serialize-creators = off
       actor.serialize-messages = off

       stream.materializer.debug.fuzzing-mode = off
     }
  """) with Directives with ScalaFutures with WithLogCapturing {

  import system.dispatcher
  implicit val mat = ActorMaterializer()

  override def expectedTestDuration = 5.minutes // because slow jenkins, generally finishes below 1 or 2 minutes

  val echo = (req: HttpRequest) => {
    req.entity.toStrict(5.second.dilated).map { entity =>
      HttpResponse().withEntity(HttpEntity(entity.data))
    }
  }
  val port = SocketUtil.temporaryServerAddress().getPort

  val binding = {
    Http2().bindAndHandleAsync(echo, "127.0.0.1", port, ExampleHttpContexts.exampleServerContext).futureValue
  }

  "H2Spec" must {

    /**
     * We explicitly list all cases we want to run, also because perhaps some of them we'll find to be not quite correct?
     * This list was obtained via a run from the console and grepping such that we get all the \\. containing lines.
     */
    val testCases =
      """
        3.5. HTTP/2 Connection Preface
        4.2. Frame Size
        4.3. Header Compression and Decompression
        5.1. Stream States
          5.1.1. Stream Identifiers
          5.1.2. Stream Concurrency
        5.3. Stream Priority
          5.3.1. Stream Dependencies
        5.5. Extending HTTP/2
        6.1. DATA
        6.2. HEADERS
        6.3. PRIORITY
        6.4. RST_STREAM
        6.5. SETTINGS
          6.5.2. Defined SETTINGS Parameters
        6.7. PING
        6.8. GOAWAY
        6.9. WINDOW_UPDATE
          6.9.1. The Flow Control Window
          6.9.2. Initial Flow Control Window Size
        6.10. CONTINUATION
        8.1. HTTP Request/Response Exchange
          8.1.2. HTTP Header Fields
            8.1.2.1. Pseudo-Header Fields
            8.1.2.2. Connection-Specific Header Fields
            8.1.2.3. Request Pseudo-Header Fields
            8.1.2.6. Malformed Requests and Responses
        8.2. Server Push
      """.split("\n").map(_.trim).filterNot(_.isEmpty)

    /** Cases that are known to fail, but we haven't triaged yet */
    val pendingTestCases = Seq(
      "4.2",
      "4.3",
      "5.1",
      "5.1.1",
      "5.5",
      "6.1",
      "6.3",
      "6.5.2",
      "6.9",
      "6.9.1",
      "6.10",
      "8.1.2",
      "8.1.2.1",
      "8.1.2.2",
      "8.1.2.6"
    )

    /**
     * Cases that are known to fail because the TCK's expectations are
     * different from our interpretation of the specs
     */
    val disabledTestCases = Seq()

    // execution of tests ------------------------------------------------------------------
    /*
    FIXME: don't fail any tests on jenkins for now
    val runningOnJenkins = System.getenv.containsKey("BUILD_NUMBER")

    if (true) {
      "pass the entire h2spec, producing junit test report" in {
        runSpec(junitOutput = new File("target/test-reports/h2spec-junit.xml"))
      }
    } else*/ {
      val testNamesWithSectionNumbers =
        testCases.zip(testCases.map(_.trim).filterNot(_.isEmpty)
          .map(l => l.take(l.lastIndexOf('.'))))

      testNamesWithSectionNumbers foreach {
        case (name, sectionNr) =>
          if (!disabledTestCases.contains(sectionNr))
            if (pendingTestCases.contains(sectionNr))
              s"pass rule: $name" ignore {
                runSpec(specSectionNumber = Some(sectionNr), junitOutput = new File(s"target/test-reports/h2spec-junit-$sectionNr.xml"))
              }
            else
              s"pass rule: $name" in {
                runSpec(specSectionNumber = Some(sectionNr), junitOutput = new File(s"target/test-reports/h2spec-junit-$sectionNr.xml"))
              }
      }
    }
    // end of execution of tests -----------------------------------------------------------

    def runSpec(specSectionNumber: Option[String], junitOutput: File): Unit = {
      junitOutput.getParentFile.mkdirs()

      val TestFailureMarker = "×" // that special character is next to test failures, so we detect them by it

      val keepAccumulating = new AtomicBoolean(true)
      val stdout = new StringBuffer()
      val stderr = new StringBuffer()

      val command = Seq( // need to use Seq[String] form for command because executable path may contain spaces
        executable,
        "-k", "-t",
        "-p", port.toString,
        "-j", junitOutput.getPath
      ) ++
        specSectionNumber.toList.flatMap(number => Seq("-s", number))

      log.debug(s"Executing h2spec: $command")
      val aggregateTckLogs = ProcessLogger(
        out => {
          if (out.contains("All tests passed")) ()
          else if (out.contains("tests, ")) ()
          else if (out.contains("===========================================")) keepAccumulating.set(false)
          else if (keepAccumulating.get) stdout.append(out + Console.RESET + "\n  ")
        },
        err => stderr.append(err)
      )

      // p.exitValue blocks until the process is terminated
      val p = command.run(aggregateTckLogs)
      val exitedWith = p.exitValue()

      val output = stdout.toString
      stderr.toString should be("")
      output shouldNot startWith("Error:")
      output shouldNot include(TestFailureMarker)
      exitedWith should be(0)
    }

    def executable =
      System.getProperty("h2spec.path").ensuring(_ != null, "h2spec.path property not defined")
  }
}
