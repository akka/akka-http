/*
 * Copyright (C) 2017-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import java.util
import java.util.Optional
import java.util.function.Function

import akka.actor.ClassicActorSystemProvider
import akka.annotation.DoNotInherit
import akka.http.impl.settings.ParserSettingsImpl
import akka.http.impl.util._
import akka.http.javadsl.model
import akka.http.scaladsl.model._
import akka.http.scaladsl.{ settings => js }
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class ParserSettings private[akka] () extends akka.http.javadsl.settings.ParserSettings { self: ParserSettingsImpl =>
  def maxUriLength: Int
  def maxMethodLength: Int
  def maxResponseReasonLength: Int
  def maxHeaderNameLength: Int
  def maxHeaderValueLength: Int
  def maxHeaderCount: Int
  def maxContentLength: Long
  def maxToStrictBytes: Long
  def maxChunkExtLength: Int
  def maxChunkSize: Int
  def maxCommentParsingDepth: Int
  def uriParsingMode: Uri.ParsingMode
  def cookieParsingMode: ParserSettings.CookieParsingMode
  def illegalHeaderWarnings: Boolean
  def ignoreIllegalHeaderFor: Set[String]
  def errorLoggingVerbosity: ParserSettings.ErrorLoggingVerbosity
  def illegalResponseHeaderNameProcessingMode: ParserSettings.IllegalResponseHeaderNameProcessingMode
  def illegalResponseHeaderValueProcessingMode: ParserSettings.IllegalResponseHeaderValueProcessingMode
  def conflictingContentTypeHeaderProcessingMode: ParserSettings.ConflictingContentTypeHeaderProcessingMode
  def headerValueCacheLimits: Map[String, Int]
  def includeTlsSessionInfoHeader: Boolean
  def includeSslSessionAttribute: Boolean
  def customMethods: String => Option[HttpMethod]
  def customStatusCodes: Int => Option[StatusCode]
  def customMediaTypes: MediaTypes.FindCustom
  def modeledHeaderParsing: Boolean

  /* Java APIs */
  override def getCookieParsingMode: js.ParserSettings.CookieParsingMode = this.cookieParsingMode
  override def getHeaderValueCacheLimits: util.Map[String, Int] = this.headerValueCacheLimits.asJava
  override def getMaxChunkExtLength = this.maxChunkExtLength
  override def getUriParsingMode: akka.http.javadsl.model.Uri.ParsingMode = this.uriParsingMode
  override def getMaxHeaderCount = this.maxHeaderCount
  override def getMaxContentLength = this.maxContentLength
  override def getMaxToStrictBytes = this.maxToStrictBytes
  override def getMaxHeaderValueLength = this.maxHeaderValueLength
  override def getIncludeTlsSessionInfoHeader = this.includeTlsSessionInfoHeader
  override def getIncludeSslSessionAttribute = this.includeSslSessionAttribute
  override def getIllegalHeaderWarnings = this.illegalHeaderWarnings
  override def getIgnoreIllegalHeaderFor = this.ignoreIllegalHeaderFor
  override def getMaxHeaderNameLength = this.maxHeaderNameLength
  override def getMaxChunkSize = this.maxChunkSize
  override def getMaxResponseReasonLength = this.maxResponseReasonLength
  override def getMaxUriLength = this.maxUriLength
  override def getMaxMethodLength = this.maxMethodLength
  override def getMaxCommentParsingDepth: Int = this.maxCommentParsingDepth
  override def getErrorLoggingVerbosity: js.ParserSettings.ErrorLoggingVerbosity = this.errorLoggingVerbosity
  override def getIllegalResponseHeaderNameProcessingMode = this.illegalResponseHeaderNameProcessingMode
  override def getIllegalResponseHeaderValueProcessingMode = this.illegalResponseHeaderValueProcessingMode
  override def getConflictingContentTypeHeaderProcessingMode = this.conflictingContentTypeHeaderProcessingMode

  override def getCustomMethods = new Function[String, Optional[akka.http.javadsl.model.HttpMethod]] {
    override def apply(t: String) = OptionConverters.toJava(self.customMethods(t))
  }
  override def getCustomStatusCodes = new Function[Int, Optional[akka.http.javadsl.model.StatusCode]] {
    override def apply(t: Int) = OptionConverters.toJava(self.customStatusCodes(t))
  }
  override def getCustomMediaTypes = new akka.japi.function.Function2[String, String, Optional[akka.http.javadsl.model.MediaType]] {
    override def apply(mainType: String, subType: String): Optional[model.MediaType] =
      OptionConverters.toJava(self.customMediaTypes(mainType, subType))
  }
  def getModeledHeaderParsing: Boolean = this.modeledHeaderParsing

  // override for more specific return type
  override def withMaxUriLength(newValue: Int): ParserSettings = self.copy(maxUriLength = newValue)
  override def withMaxMethodLength(newValue: Int): ParserSettings = self.copy(maxMethodLength = newValue)
  override def withMaxResponseReasonLength(newValue: Int): ParserSettings = self.copy(maxResponseReasonLength = newValue)
  override def withMaxHeaderNameLength(newValue: Int): ParserSettings = self.copy(maxHeaderNameLength = newValue)
  override def withMaxHeaderValueLength(newValue: Int): ParserSettings = self.copy(maxHeaderValueLength = newValue)
  override def withMaxHeaderCount(newValue: Int): ParserSettings = self.copy(maxHeaderCount = newValue)
  override def withMaxContentLength(newValue: Long): ParserSettings = self.copy(maxContentLengthSetting = Some(newValue))
  def withMaxContentLength(newValue: Option[Long]): ParserSettings = self.copy(maxContentLengthSetting = newValue)
  override def withMaxToStrictBytes(newValue: Long): ParserSettings = self.copy(maxToStrictBytes = newValue)
  override def withMaxChunkExtLength(newValue: Int): ParserSettings = self.copy(maxChunkExtLength = newValue)
  override def withMaxChunkSize(newValue: Int): ParserSettings = self.copy(maxChunkSize = newValue)
  override def withMaxCommentParsingDepth(newValue: Int): ParserSettings = self.copy(maxCommentParsingDepth = newValue)
  override def withIllegalHeaderWarnings(newValue: Boolean): ParserSettings = self.copy(illegalHeaderWarnings = newValue)
  override def withIncludeTlsSessionInfoHeader(newValue: Boolean): ParserSettings = self.copy(includeTlsSessionInfoHeader = newValue)
  override def withIncludeSslSessionAttribute(newValue: Boolean): ParserSettings = self.copy(includeSslSessionAttribute = newValue)
  override def withModeledHeaderParsing(newValue: Boolean): ParserSettings = self.copy(modeledHeaderParsing = newValue)
  override def withIgnoreIllegalHeaderFor(newValue: List[String]): ParserSettings = self.copy(ignoreIllegalHeaderFor = newValue.map(_.toLowerCase).toSet)

  // overloads for idiomatic Scala use
  def withUriParsingMode(newValue: Uri.ParsingMode): ParserSettings = self.copy(uriParsingMode = newValue)
  def withCookieParsingMode(newValue: ParserSettings.CookieParsingMode): ParserSettings = self.copy(cookieParsingMode = newValue)
  def withErrorLoggingVerbosity(newValue: ParserSettings.ErrorLoggingVerbosity): ParserSettings = self.copy(errorLoggingVerbosity = newValue)
  def withHeaderValueCacheLimits(newValue: Map[String, Int]): ParserSettings = self.copy(headerValueCacheLimits = newValue)
  def withCustomMethods(methods: HttpMethod*): ParserSettings = {
    val map = methods.map(m => m.name -> m).toMap
    self.copy(customMethods = map.get)
  }
  def withCustomStatusCodes(codes: StatusCode*): ParserSettings = {
    val map = codes.map(c => c.intValue -> c).toMap
    self.copy(customStatusCodes = map.get)
  }
  def withCustomMediaTypes(types: MediaType*): ParserSettings = {
    val map = types.map(c => (c.mainType, c.subType) -> c).toMap
    self.copy(customMediaTypes = (main, sub) => map.get((main, sub)))
  }
  def withIllegalResponseHeaderNameProcessingMode(newValue: ParserSettings.IllegalResponseHeaderNameProcessingMode): ParserSettings =
    self.copy(illegalResponseHeaderNameProcessingMode = newValue)
  def withIllegalResponseHeaderValueProcessingMode(newValue: ParserSettings.IllegalResponseHeaderValueProcessingMode): ParserSettings =
    self.copy(illegalResponseHeaderValueProcessingMode = newValue)
  def withConflictingContentTypeHeaderProcessingMode(newValue: ParserSettings.ConflictingContentTypeHeaderProcessingMode): ParserSettings =
    self.copy(conflictingContentTypeHeaderProcessingMode = newValue)
}

object ParserSettings extends SettingsCompanion[ParserSettings] {
  sealed trait CookieParsingMode extends akka.http.javadsl.settings.ParserSettings.CookieParsingMode
  object CookieParsingMode {
    case object RFC6265 extends CookieParsingMode
    case object Raw extends CookieParsingMode

    def apply(mode: String): CookieParsingMode = mode.toRootLowerCase match {
      case "rfc6265" => RFC6265
      case "raw"     => Raw
    }
  }

  sealed trait ErrorLoggingVerbosity extends akka.http.javadsl.settings.ParserSettings.ErrorLoggingVerbosity
  object ErrorLoggingVerbosity {
    case object Off extends ErrorLoggingVerbosity
    case object Simple extends ErrorLoggingVerbosity
    case object Full extends ErrorLoggingVerbosity

    def apply(string: String): ErrorLoggingVerbosity =
      string.toRootLowerCase match {
        case "off"    => Off
        case "simple" => Simple
        case "full"   => Full
        case x        => throw new IllegalArgumentException(s"[$x] is not a legal `error-logging-verbosity` setting")
      }
  }

  sealed trait IllegalResponseHeaderValueProcessingMode extends akka.http.javadsl.settings.ParserSettings.IllegalResponseHeaderValueProcessingMode
  object IllegalResponseHeaderValueProcessingMode {
    case object Error extends IllegalResponseHeaderValueProcessingMode
    case object Warn extends IllegalResponseHeaderValueProcessingMode
    case object Ignore extends IllegalResponseHeaderValueProcessingMode

    def apply(string: String): IllegalResponseHeaderValueProcessingMode =
      string.toRootLowerCase match {
        case "error"  => Error
        case "warn"   => Warn
        case "ignore" => Ignore
        case x        => throw new IllegalArgumentException(s"[$x] is not a legal `illegal-response-header-value-processing-mode` setting")
      }
  }

  sealed trait IllegalResponseHeaderNameProcessingMode extends akka.http.javadsl.settings.ParserSettings.IllegalResponseHeaderNameProcessingMode
  object IllegalResponseHeaderNameProcessingMode {
    case object Error extends IllegalResponseHeaderNameProcessingMode
    case object Warn extends IllegalResponseHeaderNameProcessingMode
    case object Ignore extends IllegalResponseHeaderNameProcessingMode

    def apply(string: String): IllegalResponseHeaderNameProcessingMode =
      string.toRootLowerCase match {
        case "error"  => Error
        case "warn"   => Warn
        case "ignore" => Ignore
        case x        => throw new IllegalArgumentException(s"[$x] is not a legal `illegal-response-header-name-processing-mode` setting")
      }
  }

  sealed trait ConflictingContentTypeHeaderProcessingMode extends akka.http.javadsl.settings.ParserSettings.ConflictingContentTypeHeaderProcessingMode
  object ConflictingContentTypeHeaderProcessingMode {
    case object Error extends ConflictingContentTypeHeaderProcessingMode
    case object First extends ConflictingContentTypeHeaderProcessingMode
    case object Last extends ConflictingContentTypeHeaderProcessingMode
    case object NoContentType extends ConflictingContentTypeHeaderProcessingMode

    def apply(string: String): ConflictingContentTypeHeaderProcessingMode =
      string.toRootLowerCase match {
        case "error"           => Error
        case "first"           => First
        case "last"            => Last
        case "no-content-type" => NoContentType
        case x                 => throw new IllegalArgumentException(s"[$x] is not a legal `conflicting-content-type-header-processing-mode` setting")
      }
  }

  @deprecated("Use forServer or forClient instead", "10.2.0")
  override def apply(config: Config): ParserSettings = ParserSettingsImpl(config)
  @deprecated("Use forServer or forClient instead", "10.2.0")
  override def apply(configOverrides: String): ParserSettings = ParserSettingsImpl(configOverrides)

  def forServer(implicit system: ClassicActorSystemProvider): ParserSettings =
    ParserSettingsImpl.forServer(system.classicSystem.settings.config)
  def forClient(implicit system: ClassicActorSystemProvider): ParserSettings =
    ParserSettingsImpl.fromSubConfig(system.classicSystem.settings.config, system.classicSystem.settings.config.getConfig("akka.http.client.parsing"))
}
