package akka.http.scaladsl.impl.parsing

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model2.HeadersFrame
import akka.stream.stage._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import com.twitter.hpack.HeaderListener

final class RequestHeaderDecompression extends GraphStage[FlowShape[HeadersFrame, HttpRequest]] {
  import RequestHeaderDecompression._

  // FIXME Make configurable
  final val maxHeaderSize = 4096
  final val maxHeaderTableSize = 4096
  final val name = "name".getBytes()
  final val value = "value".getBytes()
  final val sensitive = false

  private final val ColonByte = ':'.toByte

  val in = Inlet[HeadersFrame]("HeaderDecompression.in")
  val out = Outlet[HttpRequest]("HeaderDecompression.out")
  override val shape = FlowShape.of(in, out)

  // format: OFF
  override def createLogic(inheritedAttributes: Attributes) =
    new GraphStageLogic(shape)
      with InHandler with OutHandler
      with HeaderListener {
      // format: ON

      val zeroRequest = HttpRequest().withProtocol(HttpProtocols.`HTTP/2.0`)
      private[this] var beingBuiltRequest: HttpRequest = zeroRequest // TODO replace with "RequestBuilder" that's more efficient

      val decoder = new com.twitter.hpack.Decoder(maxHeaderSize, maxHeaderTableSize)

      override def onPush(): Unit = {
        val headersFrame = grab(in)

        val is = ByteStringInputStream(headersFrame.headerBlockFragment)
        decoder.decode(is, this) // this: HeaderListener (invoked synchronously)

        pushIfReady(headersFrame)
      }

      override def onPull(): Unit =
        pull(in)

      // this is invoked synchronously from decoder.decode()
      override def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = {
        val nameString = new String(name) // FIXME wasteful :-(
        val valueString = new String(value)

        // FIXME lookup here must be optimised
        if (name.head == ColonByte) {
          nameString match {
            case ":method" ⇒
              val method = HttpMethods.getForKey(valueString)
                .getOrElse(throw new IllegalArgumentException(s"Unknown HttpMethod! Was: '$valueString'."))

              // FIXME only copy if value has changed to avoid churning allocs
              beingBuiltRequest = beingBuiltRequest.copy(method = method)

            case ":path" ⇒
              // FIXME only copy if value has changed to avoid churning allocs
              beingBuiltRequest = beingBuiltRequest.copy(uri = valueString)

            // TODO handle all special headers

            case unknown ⇒
              throw new Exception(s": prefixed header should be emitted well-typed! Was: '${new String(unknown)}'. This is a bug.")
          }
        } else {
          // TODO handle all typed headers
          RawHeader(nameString, new String(value))
        }
      }

      setHandlers(in, out, this)

      override def onUpstreamFinish(): Unit = {
        completeStage()
      }

      private def pushIfReady(headersFrame: HeadersFrame): Unit = {
        if (headersFrame.endHeaders) {
          push(out, beingBuiltRequest)
          beingBuiltRequest = zeroRequest
        } else {
          // else we're awaiting a CONTINUATION frame with the remaining headers
          pull(in)
        }
      }

    }

  // FIXME actually convert to the real typed model
  // FIXME carry-on sesitive flag?
  def convertToModelledHeader(data: HeaderData): HttpHeader =
    RawHeader(new String(data.name), new String(data.value))

}

object RequestHeaderDecompression {
  final case class HeaderData(name: Array[Byte], value: Array[Byte], sensitive: Boolean) {
    override lazy val toString =
      if (sensitive) s"${getClass.getName}(${new String(name)}, <sensitive-value>)"
      else s"${getClass.getName}(${new String(name)}, ${new String(value)})"
  }
}
