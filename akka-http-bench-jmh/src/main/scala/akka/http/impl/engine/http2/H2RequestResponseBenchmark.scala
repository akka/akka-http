/*
 * Copyright (C) 2021-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.FrameEvent.{ DataFrame, HeadersFrame }
import akka.http.impl.engine.http2.framing.FrameRenderer
import akka.http.scaladsl.model.HttpEntity.{ Chunk, LastChunk }
import akka.http.scaladsl.model.{ AttributeKeys, ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, Trailer }
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations.Param

trait H2RequestResponseBenchmark extends HPackEncodingSupport {
  @Param(Array("1"))
  var minStrictEntitySize: String = _

  @Param(Array("empty", "singleframe"))
  var requestbody: String = _

  @Param(Array("strict", "closedelimited" /* Not enable by default:, "chunked", "empty"*/ ))
  var responsetype: String = _

  protected var response: HttpResponse = _

  private val requestBytes = ByteString("abcde")
  private def requestWithoutBody(streamId: Int): ByteString =
    FrameRenderer.render(HeadersFrame(streamId, endStream = true, endHeaders = true, headerBlock(streamId), None))
  private def requestWithSingleFrameBody(streamId: Int): ByteString =
    FrameRenderer.render(HeadersFrame(streamId, endStream = false, endHeaders = true, headerBlock(streamId), None)) ++
      FrameRenderer.render(DataFrame(streamId, endStream = true, requestBytes))

  private var firstRequestHeaderBlock: ByteString = _
  // use header compression for subsequent requests
  private var subsequentRequestHeaderBlock: ByteString = _
  private def headerBlock(streamId: Int): ByteString =
    if (streamId == 1) firstRequestHeaderBlock
    else subsequentRequestHeaderBlock

  protected var requestDataCreator: Int => ByteString = _
  protected var request: HttpRequest = _

  def numRequests: Int
  lazy val config =
    ConfigFactory.parseString(
      s"""
           akka.actor.default-dispatcher.fork-join-executor.parallelism-max = 1
           akka.http.server.http2.max-concurrent-streams = $numRequests # needs to be >= `numRequests`
           akka.http.server.http2.min-collect-strict-entity-size = $minStrictEntitySize
           #akka.loglevel = debug
           #akka.http.server.log-unencrypted-network-bytes = 100
         """)
      .withFallback(ConfigFactory.load())

  def initRequestResponse(): Unit = {
    requestbody match {
      case "empty" =>
        request = HttpRequest(method = HttpMethods.POST, uri = "http://www.example.com/")
        requestDataCreator = requestWithoutBody _
      case "singleframe" =>
        request = HttpRequest(method = HttpMethods.POST, uri = "http://www.example.com/", entity = HttpEntity(requestBytes))
        requestDataCreator = requestWithSingleFrameBody _
    }
    initRequestHeaderBlocks()

    val trailerHeader = RawHeader("grpc-status", "9")
    val responseBody = ByteString("hello")
    val baseResponse = HPackSpecExamples.FirstResponse.removeHeader("date")
    response = responsetype match {
      case "empty" =>
        baseResponse
          .withEntity(HttpEntity.Empty)
          .addAttribute(AttributeKeys.trailer, Trailer(trailerHeader :: Nil))
      case "closedelimited" =>
        baseResponse
          .withEntity(HttpEntity.CloseDelimited(ContentTypes.`text/plain(UTF-8)`, Source.single(responseBody)))
          .addAttribute(AttributeKeys.trailer, Trailer(trailerHeader :: Nil))
      case "chunked" =>
        baseResponse
          .withEntity(HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, Source(Chunk(responseBody) :: LastChunk(trailer = trailerHeader :: Nil) :: Nil)))
      case "strict" =>
        baseResponse
          .withEntity(HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, responseBody))
          .addAttribute(AttributeKeys.trailer, Trailer(trailerHeader :: Nil))
    }
  }
  private def initRequestHeaderBlocks(): Unit = {
    firstRequestHeaderBlock = encodeRequestHeaders(request)
    subsequentRequestHeaderBlock = encodeRequestHeaders(request) // second invocation will lead to different result
  }

}
