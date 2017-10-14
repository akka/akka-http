/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.server

import akka.NotUsed
import akka.http.impl.engine.server.SwitchableIdleTimeoutBidi.{ DisableTimeout, EnableTimeout }
import akka.http.scaladsl.model.ResponseEntity
import akka.stream.scaladsl.{ Flow, Source }
import akka.util.ByteString

/**
 * This is a helper object for WIP-71 and should not be merged to master.
 */
object SwitchableIdleTimeoutPrototype {

  private val switchOnString = "#71-idle-timeout-enable"
  private val switchOffString = "#71-idle-timeout-disable"

  private def stringToEntityChunk(string: String): ByteString = ByteString(s"${string.length.toLong.toHexString}\r\n$string\r\n")

  val identifyIdleTimeoutSwitchBytestrings: Flow[ByteString, Either[SwitchableIdleTimeoutBidi.TimeoutSwitch, ByteString], NotUsed] =
    Flow[ByteString]
      .map {
        case bs if bs == stringToEntityChunk(switchOffString) ⇒
          Left(DisableTimeout)
        case bs if bs == stringToEntityChunk(switchOnString) ⇒
          Left(EnableTimeout)
        case bs ⇒
          Right(bs)
      }

  def embedIdleTimeoutSwitchByteStringsInEntity(entity: ResponseEntity): ResponseEntity = {
    val codeEmbeddingFlow = Flow[ByteString]
      .prepend(Source.single(ByteString(switchOffString)))
      .concat(Source.single(ByteString(switchOnString)))

    entity.transformDataBytes(codeEmbeddingFlow)
  }
}
