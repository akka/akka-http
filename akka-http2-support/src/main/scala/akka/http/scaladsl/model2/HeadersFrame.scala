package akka.http.scaladsl.model2

import akka.util.ByteString

/**
 * {{{
 * +---------------+
 * |Pad Length? (8)|
 * +-+-------------+-----------------------------------------------+
 * |E|                 Stream Dependency? (31)                     |
 * +-+-------------+-----------------------------------------------+
 * |  Weight? (8)  |
 * +-+-------------+-----------------------------------------------+
 * |                   Header Block Fragment (*)                 ...
 * +---------------------------------------------------------------+
 * |                           Padding (*)                       ...
 * +---------------------------------------------------------------+
 * }}}
 *
 * Lack of presence of a field is marked as `-1`.
 *
 * @param headerBlockFragment A header block fragment (Section 4.3).
 */
final case class HeadersFrame(
  streamId:            Int,
  endStream:           Boolean,
  endHeaders:          Boolean,
  headerBlockFragment: ByteString) extends FrameEvent

object HeadersFrame {

  object Flags {
    /**
     * When set, bit 0 indicates that the header block (Section 4.3) is the last that the endpoint will send for the identified stream.
     *
     * A HEADERS frame carries the END_STREAM flag that signals the end of a stream. However, a HEADERS frame with the
     * END_STREAM flag set can be followed by CONTINUATION frames on the same stream. Logically, the CONTINUATION
     * frames are part of the HEADERS frame.
     */
    val END_STREAM = 0x1

    /**
     * When set, bit 2 indicates that this frame contains an entire header block (Section 4.3) and is not followed by any CONTINUATION frames.
     *
     * A HEADERS frame without the END_HEADERS flag set MUST be followed by a CONTINUATION frame for the same stream.
     * A receiver MUST treat the receipt of any other type of frame or a frame on a different stream as a connection
     * error (Section 5.4.1) of type PROTOCOL_ERROR.
     */
    val END_HEADERS = 0x4

    /**
     * When set, bit 3 indicates that the Pad Length field and any padding that it describes are present.
     */
    val PADDED = 0x8

    /**
     * When set, bit 5 indicates that the Exclusive Flag (E), Stream Dependency, and Weight fields are present; see Section 5.3.
     */
    val PRIORITY = 0x20
  }

}
