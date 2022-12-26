/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.scaladsl.settings.ParserSettings.{ ConflictingContentTypeHeaderProcessingMode, CookieParsingMode, ErrorLoggingVerbosity, IllegalResponseHeaderValueProcessingMode, IllegalResponseHeaderNameProcessingMode }
import akka.util.ConstantFun
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import akka.http.scaladsl.model._
import akka.http.impl.util._
import akka.http.scaladsl.settings.ParserSettings

/** INTERNAL API */
@InternalApi
private[akka] final case class ParserSettingsImpl(
  maxUriLength:                               Int,
  maxMethodLength:                            Int,
  maxResponseReasonLength:                    Int,
  maxHeaderNameLength:                        Int,
  maxHeaderValueLength:                       Int,
  maxHeaderCount:                             Int,
  maxContentLengthSetting:                    Option[Long],
  maxToStrictBytes:                           Long,
  maxChunkExtLength:                          Int,
  maxChunkSize:                               Int,
  maxCommentParsingDepth:                     Int,
  uriParsingMode:                             Uri.ParsingMode,
  cookieParsingMode:                          CookieParsingMode,
  illegalHeaderWarnings:                      Boolean,
  ignoreIllegalHeaderFor:                     Set[String],
  errorLoggingVerbosity:                      ErrorLoggingVerbosity,
  illegalResponseHeaderNameProcessingMode:    IllegalResponseHeaderNameProcessingMode,
  illegalResponseHeaderValueProcessingMode:   IllegalResponseHeaderValueProcessingMode,
  conflictingContentTypeHeaderProcessingMode: ConflictingContentTypeHeaderProcessingMode,
  headerValueCacheLimits:                     Map[String, Int],
  includeTlsSessionInfoHeader:                Boolean,
  includeSslSessionAttribute:                 Boolean,
  modeledHeaderParsing:                       Boolean,
  customMethods:                              String => Option[HttpMethod],
  customStatusCodes:                          Int => Option[StatusCode],
  customMediaTypes:                           MediaTypes.FindCustom)
  extends akka.http.scaladsl.settings.ParserSettings {

  require(maxUriLength > 0, "max-uri-length must be > 0")
  require(maxMethodLength > 0, "max-method-length must be > 0")
  require(maxResponseReasonLength > 0, "max-response-reason-length must be > 0")
  require(maxHeaderNameLength > 0, "max-header-name-length must be > 0")
  require(maxHeaderValueLength > 0, "max-header-value-length must be > 0")
  require(maxHeaderCount > 0, "max-header-count must be > 0")
  require(maxContentLengthSetting.forall(_ >= 0), "if set max-content-length must be >= 0")
  require(maxChunkExtLength > 0, "max-chunk-ext-length must be > 0")
  require(maxChunkSize > 0, "max-chunk-size must be > 0")
  require(maxCommentParsingDepth > 0, "max-comment-parsing-depth must be > 0")

  override val defaultHeaderValueCacheLimit: Int = headerValueCacheLimits("default")

  override def headerValueCacheLimit(headerName: String): Int =
    headerValueCacheLimits.getOrElse(headerName, defaultHeaderValueCacheLimit)

  override def maxContentLength: Long =
    maxContentLengthSetting.getOrElse(
      throw new IllegalStateException("Generic ParserSettings were created missing a server/client specific max-content-length setting. " +
        "Adapt settings in the config file or use ParserSettings.forClient or ParserSetting.forServer instead of ParserSettings.apply / ParserSettings.create.")
    )

  override def productPrefix = "ParserSettings"
}

object ParserSettingsImpl extends SettingsCompanionImpl[ParserSettingsImpl]("akka.http.parsing") {

  private[this] val noCustomMethods: String => Option[HttpMethod] = ConstantFun.scalaAnyToNone
  private[this] val noCustomStatusCodes: Int => Option[StatusCode] = ConstantFun.scalaAnyToNone
  private[ParserSettingsImpl] val noCustomMediaTypes: (String, String) => Option[MediaType] = ConstantFun.scalaAnyTwoToNone

  def forServer(root: Config): ParserSettings =
    fromSubConfig(root, root.getConfig("akka.http.server.parsing"))

  def fromSubConfig(root: Config, inner: Config): ParserSettingsImpl = {
    val c = inner.withFallback(root.getConfig(prefix))
    val cacheConfig = c getConfig "header-cache"

    new ParserSettingsImpl(
      c.getIntBytes("max-uri-length"),
      c.getIntBytes("max-method-length"),
      c.getIntBytes("max-response-reason-length"),
      c.getIntBytes("max-header-name-length"),
      c.getIntBytes("max-header-value-length"),
      c.getIntBytes("max-header-count"),
      c.ifDefined("max-content-length", _.getPossiblyInfiniteBytes(_)),
      c.getPossiblyInfiniteBytes("max-to-strict-bytes"),
      c.getIntBytes("max-chunk-ext-length"),
      c.getIntBytes("max-chunk-size"),
      c.getInt("max-comment-parsing-depth"),
      Uri.ParsingMode(c.getString("uri-parsing-mode")),
      CookieParsingMode(c.getString("cookie-parsing-mode")),
      c.getBoolean("illegal-header-warnings"),
      c.getStringList("ignore-illegal-header-for").asScala.map(_.toLowerCase).toSet,
      ErrorLoggingVerbosity(c.getString("error-logging-verbosity")),
      IllegalResponseHeaderNameProcessingMode(c.getString("illegal-response-header-name-processing-mode")),
      IllegalResponseHeaderValueProcessingMode(c.getString("illegal-response-header-value-processing-mode")),
      ConflictingContentTypeHeaderProcessingMode(c.getString("conflicting-content-type-header-processing-mode")),
      cacheConfig.entrySet.asScala.iterator.map(kvp => kvp.getKey -> cacheConfig.getInt(kvp.getKey)).toMap,
      c.getBoolean("tls-session-info-header"),
      c.getBoolean("ssl-session-attribute"),
      c.getBoolean("modeled-header-parsing"),
      noCustomMethods,
      noCustomStatusCodes,
      noCustomMediaTypes
    )
  }

}

