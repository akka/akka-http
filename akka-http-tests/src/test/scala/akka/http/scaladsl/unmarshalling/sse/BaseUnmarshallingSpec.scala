/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http
package scaladsl
package unmarshalling
package sse

import akka.actor.ActorSystem
import akka.stream.Materializer
import org.scalatest.{ BeforeAndAfterAll, Suite }

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

trait BaseUnmarshallingSpec extends BeforeAndAfterAll { this: Suite =>

  protected implicit val system: ActorSystem =
    ActorSystem()

  protected implicit val mat: Materializer =
    Materializer(system)

  override protected def afterAll() = {
    Await.ready(system.terminate(), 42.seconds)
    super.afterAll()
  }
}
