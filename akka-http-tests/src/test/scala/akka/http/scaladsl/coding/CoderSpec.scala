/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream }
import java.util.zip.DataFormatException

import akka.NotUsed

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.ThreadLocalRandom

import scala.util.control.NoStackTrace
import org.scalatest.Inspectors
import akka.util.ByteString
import akka.stream.scaladsl.{ Sink, Source }
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest }
import akka.http.scaladsl.model.HttpMethods._
import akka.http.impl.util._
import akka.testkit._
import scala.annotation.nowarn
import org.scalatest.wordspec.AnyWordSpec

@nowarn("msg=deprecated .* is internal API")
abstract class CoderSpec extends AnyWordSpec with CodecSpecSupport with Inspectors {
  protected def Coder: Coder
  protected def newDecodedInputStream(underlying: InputStream): InputStream
  protected def newEncodedOutputStream(underlying: OutputStream): OutputStream

  case object AllDataAllowed extends Exception with NoStackTrace
  protected def corruptInputCheck: Boolean = true

  def extraTests(): Unit = {}

  s"The ${Coder.encoding.value} codec" should {
    "produce valid data on immediate finish" in {
      streamDecode(Coder.newCompressor.finish()) should readAs(emptyText)
    }
    "properly encode an empty string" in {
      streamDecode(ourEncode(emptyTextBytes)) should readAs(emptyText)
    }
    "properly decode an empty string" in {
      ourDecode(streamEncode(emptyTextBytes)) should readAs(emptyText)
    }
    "properly round-trip encode/decode an empty string" in {
      ourDecode(ourEncode(emptyTextBytes)) should readAs(emptyText)
    }
    "properly encode a small string" in {
      streamDecode(ourEncode(smallTextBytes)) should readAs(smallText)
    }
    "properly decode a small string" in {
      ourDecode(streamEncode(smallTextBytes)) should readAs(smallText)
    }
    "properly round-trip encode/decode a small string" in {
      ourDecode(ourEncode(smallTextBytes)) should readAs(smallText)
    }
    "properly encode a large string" in {
      streamDecode(ourEncode(largeTextBytes)) should readAs(largeText)
    }
    "properly decode a large string" in {
      ourDecode(streamEncode(largeTextBytes)) should readAs(largeText)
    }
    "properly round-trip encode/decode a large string" in {
      ourDecode(ourEncode(largeTextBytes)) should readAs(largeText)
    }
    "properly round-trip encode/decode an HttpRequest" in {
      val request = HttpRequest(POST, entity = HttpEntity(largeText))
      Coder.decodeMessage(Coder.encodeMessage(request)).toStrict(3.seconds.dilated).awaitResult(3.seconds.dilated) should equal(request)
    }

    if (corruptInputCheck) {
      "throw an error on corrupt input" in {
        (the[RuntimeException] thrownBy {
          ourDecode(corruptContent)
        }).ultimateCause should be(a[DataFormatException])
      }
    }

    "not throw an error if a subsequent block is corrupt" in {
      pending // FIXME: should we read as long as possible and only then report an error, that seems somewhat arbitrary
      ourDecode(Seq(encode("Hello,"), encode(" dear "), corruptContent).join) should readAs("Hello, dear ")
    }
    "decompress in very small chunks" in {
      val compressed = encode("Hello")

      decodeChunks(Source(Vector(compressed.take(10), compressed.drop(10)))) should readAs("Hello")
    }
    "support chunked round-trip encoding/decoding" in {
      val chunks = largeTextBytes.grouped(512).toVector
      val comp = Coder.newCompressor
      val compressedChunks = chunks.map { chunk => comp.compressAndFlush(chunk) } :+ comp.finish()
      val uncompressed = decodeFromIterator(() => compressedChunks.iterator)

      uncompressed should readAs(largeText)
    }
    "works for any split in prefix + suffix" in {
      val compressed = streamEncode(smallTextBytes)
      def tryWithPrefixOfSize(prefixSize: Int): Unit = {
        val prefix = compressed.take(prefixSize)
        val suffix = compressed.drop(prefixSize)

        decodeChunks(Source(prefix :: suffix :: Nil)) should readAs(smallText)
      }
      (0 to compressed.size).foreach(tryWithPrefixOfSize)
    }
    "works for chunked compressed data of sizes just above 1024" in {
      val comp = Coder.newCompressor
      val inputBytes = ByteString("""{"baseServiceURL":"http://www.acme.com","endpoints":{"assetSearchURL":"/search","showsURL":"/shows","mediaContainerDetailURL":"/container","featuredTapeURL":"/tape","assetDetailURL":"/asset","moviesURL":"/movies","recentlyAddedURL":"/recent","topicsURL":"/topics","scheduleURL":"/schedule"},"urls":{"aboutAweURL":"www.foobar.com"},"channelName":"Cool Stuff","networkId":"netId","slotProfile":"slot_1","brag":{"launchesUntilPrompt":10,"daysUntilPrompt":5,"launchesUntilReminder":5,"daysUntilReminder":2},"feedbackEmailAddress":"feedback@acme.com","feedbackEmailSubject":"Commends from User","splashSponsor":[],"adProvider":{"adProviderProfile":"","adProviderProfileAndroid":"","adProviderNetworkID":0,"adProviderSiteSectionNetworkID":0,"adProviderVideoAssetNetworkID":0,"adProviderSiteSectionCustomID":{},"adProviderServerURL":"","adProviderLiveVideoAssetID":""},"update":[{"forPlatform":"ios","store":{"iTunes":"www.something.com"},"minVer":"1.2.3","notificationVer":"1.2.5"},{"forPlatform":"android","store":{"amazon":"www.something.com","play":"www.something.com"},"minVer":"1.2.3","notificationVer":"1.2.5"}],"tvRatingPolicies":[{"type":"sometype","imageKey":"tv_rating_small","durationMS":15000,"precedence":1},{"type":"someothertype","imageKey":"tv_rating_big","durationMS":15000,"precedence":2}],"exts":{"adConfig":{"globals":{"#{adNetworkID}":"2620","#{ssid}":"usa_tveapp"},"iPad":{"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/shows","adSize":[{"#{height}":90,"#{width}":728}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad&sz=1x1&t=&c=#{doubleclickrandom}"},"watchwithshowtile":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/watchwithshowtile","adSize":[{"#{height}":120,"#{width}":240}]},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"iPadRetina":{"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/shows","adSize":[{"#{height}":90,"#{width}":728}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad&sz=1x1&t=&c=#{doubleclickrandom}"},"watchwithshowtile":{"adMobAdUnitID":"/2620/usa_tveapp_ipad/watchwithshowtile","adSize":[{"#{height}":120,"#{width}":240}]},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_ipad/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"iPhone":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"iPhoneRetina":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_iphone/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"Tablet":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/home","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"TabletHD":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/home","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}","adSize":[{"#{height}":90,"#{width}":728},{"#{height}":50,"#{width}":320},{"#{height}":50,"#{width}":300}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_androidtab/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"Phone":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_android/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}},"PhoneHD":{"home":{"adMobAdUnitID":"/2620/usa_tveapp_android/home","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"showlist":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"episodepage":{"adMobAdUnitID":"/2620/usa_tveapp_android/shows/#{SHOW_NAME}","adSize":[{"#{height}":50,"#{width}":300},{"#{height}":50,"#{width}":320}]},"launch":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android&sz=1x1&t=&c=#{doubleclickrandom}"},"showpage":{"doubleClickCallbackURL":"http://pubads.g.doubleclick.net/gampad/ad?iu=/2620/usa_tveapp_android/shows/#{SHOW_NAME}&sz=1x1&t=&c=#{doubleclickrandom}"}}}}}""", "utf8")
      val compressed = comp.compressAndFinish(inputBytes)

      ourDecode(compressed) should equal(inputBytes)
    }

    "shouldn't produce huge ByteStrings for some input" in {
      val array = new Array[Byte](100007)
      val random = ThreadLocalRandom.current()
      random.nextBytes(array)
      val compressed = streamEncode(ByteString(array))
      val limit = 10000
      val resultBs =
        Source.single(compressed)
          .via(Coder.withMaxBytesPerChunk(limit).decoderFlow)
          .runWith(Sink.seq)
          .awaitResult(3.seconds.dilated)

      forAll(resultBs) { bs =>
        bs.length should be <= limit
      }
      val result = resultBs.reduce(_ ++ _)
      result should equal(array)
    }

    "be able to decode chunk-by-chunk (depending on input chunks)" in {
      val minLength = 100
      val maxLength = 1000
      val numElements = 1000

      val random = ThreadLocalRandom.current()
      val sizes = Seq.fill(numElements)(random.nextInt(minLength, maxLength))
      def createByteString(size: Int): ByteString =
        ByteString(Array.fill(size)(1.toByte))

      val sizesAfterRoundtrip =
        Source.fromIterator(() => sizes.toIterator.map(createByteString))
          .via(Coder.encoderFlow)
          .via(Coder.decoderFlow)
          .runFold(Seq.empty[Int])(_ :+ _.size)

      sizes shouldEqual sizesAfterRoundtrip.awaitResult(3.seconds.dilated)
    }

    extraTests()
  }

  def encode(s: String) = ourEncode(ByteString(s, "UTF8"))
  def ourEncode(bytes: ByteString): ByteString = Coder.encodeAsync(bytes).awaitResult(3.seconds.dilated)
  def ourDecode(bytes: ByteString): ByteString = Coder.decode(bytes).awaitResult(3.seconds.dilated)

  lazy val corruptContent = {
    val content = encode(largeText).toArray
    content(14) = 26.toByte
    ByteString(content)
  }

  def streamEncode(bytes: ByteString): ByteString = {
    val output = new ByteArrayOutputStream()
    val gos = newEncodedOutputStream(output)
    gos.write(bytes.toArray)
    gos.flush()
    gos.close()
    ByteString(output.toByteArray)
  }

  def streamDecode(bytes: ByteString): ByteString = {
    val output = new ByteArrayOutputStream()
    val input = newDecodedInputStream(new ByteArrayInputStream(bytes.toArray))

    val buffer = new Array[Byte](500)
    @tailrec def copy(from: InputStream, to: OutputStream): Unit = {
      val read = from.read(buffer)
      if (read >= 0) {
        to.write(buffer, 0, read)
        copy(from, to)
      }
    }

    copy(input, output)
    ByteString(output.toByteArray)
  }

  def decodeChunks(input: Source[ByteString, NotUsed]): ByteString =
    input.via(Coder.decoderFlow).join.awaitResult(3.seconds.dilated)

  def decodeFromIterator(iterator: () => Iterator[ByteString]): ByteString =
    Await.result(Source.fromIterator(iterator).via(Coder.decoderFlow).join, 3.seconds.dilated)

  implicit class EnhancedThrowable(val throwable: Throwable) {
    def ultimateCause: Throwable = {
      @tailrec def rec(ex: Throwable): Throwable =
        if (ex.getCause == null) ex
        else rec(ex.getCause)

      rec(throwable)
    }
  }
}
