package akka.http.scaladsl.impl.parsing

import java.io.{ ByteArrayInputStream, InputStream }

import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model2.HeadersFrame
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.util.ByteString.ByteString1C
import akka.util.{ ByteString, CompactByteString }

object ByteStringInputStream {

  def apply(bs: ByteString): InputStream =
    bs match {
      case cs: ByteString1C ⇒
        // TODO optimise, ByteString needs to expose InputStream (esp if array backed, nice!)
        new ByteArrayInputStream(cs.toArray)
      case _ ⇒
        // NOTE: We actually measured recently, and compact + use array was pretty good usually
        apply(bs.compact)
    }
}
