/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import akka.http.impl.model.parser.CharacterClasses
import akka.http.javadsl.model.headers
import akka.parboiled2.CharPredicate
import java.util.{ Optional, OptionalLong }

import akka.http.scaladsl.model.DateTime
import akka.http.impl.util._
import akka.http.javadsl.{ model => jm }
import akka.http.impl.util.JavaMapping.Implicits._

import scala.compat.java8.OptionConverters._

/**
 * for a full definition of the http cookie header fields, see
 * http://tools.ietf.org/html/rfc6265
 * This class is sealed abstract to prevent generation of default apply method in companion
 */
sealed abstract case class HttpCookiePair private (
  name:  String,
  value: String) extends jm.headers.HttpCookiePair with ToStringRenderable {

  def render[R <: Rendering](r: R): r.type = r ~~ name ~~ '=' ~~ value
  def toCookie: HttpCookie = HttpCookie.createFromPair(this)
}
object HttpCookiePair {
  def apply(pair: (String, String)): HttpCookiePair = apply(pair._1, pair._2)
  def apply(name: String, value: String): HttpCookiePair = {
    HttpCookiePair.validate(name, value)
    new HttpCookiePair(name, value) {}
  }

  def raw(pair: (String, String)): HttpCookiePair = raw(pair._1, pair._2)
  def raw(name: String, value: String): HttpCookiePair = {
    HttpCookiePair.validateRaw(name, value)
    new HttpCookiePair(name, value) {}
  }

  private[http] def validate(name: String, value: String): Unit = {
    import HttpCookie._
    require(nameChars.matchesAll(name), s"'${nameChars.firstMismatch(name).get}' not allowed in cookie name ('$name')")
    require(valueChars.matchesAll(value), s"'${valueChars.firstMismatch(value).get}' not allowed in cookie content ('$value')")
  }
  private[http] def validateRaw(name: String, value: String): Unit = {
    import HttpCookie._
    require(nameChars.matchesAll(name), s"'${nameChars.firstMismatch(name).get}' not allowed in cookie name ('$name')")
    require(rawValueChars.matchesAll(value), s"'${rawValueChars.firstMismatch(value).get}' not allowed in cookie content ('$value')")
  }
}

/**
 * for a full definition of the http cookie header fields, see
 * http://tools.ietf.org/html/rfc6265
 */
final class HttpCookie private[http] (
  name:          String,
  value:         String,
  val expires:   Option[DateTime],
  val maxAge:    Option[Long],
  val domain:    Option[String],
  val path:      Option[String],
  secure:        Boolean,
  httpOnly:      Boolean,
  val extension: Option[String],
  val sameSite:  Option[SameSite]) extends jm.headers.HttpCookie with ToStringRenderable with Product with Serializable with Equals {

  @deprecated("for binary compatibility", since = "10.2.0")
  def this(
    name:      String,
    value:     String,
    expires:   Option[DateTime] = None,
    maxAge:    Option[Long]     = None,
    domain:    Option[String]   = None,
    path:      Option[String]   = None,
    secure:    Boolean          = false,
    httpOnly:  Boolean          = false,
    extension: Option[String]   = None) = this(name, value, expires, maxAge, domain, path, secure, httpOnly, extension, None)

  // This is used internally when parsing SameSite attribute.
  private[http] def withSameSite(sameSite: Option[SameSite]) = new HttpCookie(name, value, expires, maxAge, domain, path, secure, httpOnly, extension, sameSite)

  def copy(
    name:      String           = this.name,
    value:     String           = this.value,
    expires:   Option[DateTime] = this.expires,
    maxAge:    Option[Long]     = this.maxAge,
    domain:    Option[String]   = this.domain,
    path:      Option[String]   = this.path,
    secure:    Boolean          = this.secure,
    httpOnly:  Boolean          = this.httpOnly,
    extension: Option[String]   = this.extension) = new HttpCookie(name, value, expires, maxAge, domain, path, secure, httpOnly, extension, None)

  override def productArity: Int = 9

  override def productElement(n: Int): Any = n match {
    case 0 => name
    case 1 => value
    case 2 => expires
    case 3 => maxAge
    case 4 => domain
    case 5 => path
    case 6 => secure
    case 7 => httpOnly
    case 8 => extension
  }

  override def canEqual(that: Any): Boolean = that.isInstanceOf[HttpCookie]

  override def equals(obj: Any): Boolean = obj match {
    case that: HttpCookie =>
      this.canEqual(that) &&
        this.name == that.name &&
        this.value == that.value &&
        this.expires == that.expires &&
        this.maxAge == that.maxAge &&
        this.domain == that.domain &&
        this.path == that.path &&
        this.secure == that.secure &&
        this.httpOnly == that.httpOnly &&
        this.extension == that.extension &&
        this.sameSite == that.sameSite
    case _ => false
  }

  /** Returns the name/value pair for this cookie, to be used in [[Cookie]] headers. */
  def pair: HttpCookiePair = HttpCookiePair(name, value)

  // TODO: suppress running these requires for cookies created from our header parser

  import HttpCookie._

  HttpCookiePair.validate(name, value)
  require(domain.forall(domainChars.matchesAll), s"'${domainChars.firstMismatch(domain.get).get}' not allowed in cookie domain ('${domain.get}')")
  require(path.forall(pathOrExtChars.matchesAll), s"'${pathOrExtChars.firstMismatch(path.get).get}' not allowed in cookie path ('${path.get}')")
  require(extension.forall(pathOrExtChars.matchesAll), s"'${pathOrExtChars.firstMismatch(extension.get).get}' not allowed in cookie extension ('${extension.get}')")

  def render[R <: Rendering](r: R): r.type = {
    r ~~ name ~~ '=' ~~ value
    if (expires.isDefined) expires.get.renderRfc1123DateTimeString(r ~~ "; Expires=")
    if (maxAge.isDefined) r ~~ "; Max-Age=" ~~ maxAge.get
    if (domain.isDefined) r ~~ "; Domain=" ~~ domain.get
    if (path.isDefined) r ~~ "; Path=" ~~ path.get
    if (secure) r ~~ "; Secure"
    if (httpOnly) r ~~ "; HttpOnly"
    if (extension.isDefined) r ~~ ';' ~~ ' ' ~~ extension.get
    if (sameSite.isDefined) r ~~ "; SameSite=" ~~ sameSite.get
    r
  }

  override def name(): String = this.name
  override def value(): String = this.value
  override def secure(): Boolean = this.secure
  override def httpOnly(): Boolean = this.httpOnly

  /** Java API */
  def getSameSite: Optional[jm.headers.SameSite] = sameSite.map(_.asJava).asJava
  /** Java API */
  def getExtension: Optional[String] = extension.asJava
  /** Java API */
  def getPath: Optional[String] = path.asJava
  /** Java API */
  def getDomain: Optional[String] = domain.asJava
  /** Java API */
  def getMaxAge: OptionalLong = maxAge.asPrimitive
  /** Java API */
  def getExpires: Optional[jm.DateTime] = expires.map(_.asJava).asJava
  /** Java API */
  def withExpires(dateTime: jm.DateTime): headers.HttpCookie = copy(expires = Some(dateTime.asScala))
  /** Java API */
  def withDomain(domain: String): headers.HttpCookie = copy(domain = Some(domain))
  /** Java API */
  def withPath(path: String): headers.HttpCookie = copy(path = Some(path))
  /** Java API */
  def withMaxAge(maxAge: Long): headers.HttpCookie = copy(maxAge = Some(maxAge))
  /** Java API */
  def withSecure(secure: Boolean): headers.HttpCookie = copy(secure = secure)
  /** Java API */
  def withHttpOnly(httpOnly: Boolean): headers.HttpCookie = copy(httpOnly = httpOnly)
  /** Java API */
  def withSameSite(sameSite: jm.headers.SameSite): headers.HttpCookie = new HttpCookie(name, value, expires, maxAge, domain, path, secure, httpOnly, extension, Option(sameSite.asScala()))
  /** Java API */
  def withSameSite(sameSite: Optional[jm.headers.SameSite]): headers.HttpCookie = new HttpCookie(name, value, expires, maxAge, domain, path, secure, httpOnly, extension, sameSite.asScala.map(_.asScala()))
  /** Java API */
  def withExtension(extension: String): headers.HttpCookie = copy(extension = Some(extension))
}

object HttpCookie {

  def apply(
    name:      String,
    value:     String,
    expires:   Option[DateTime] = None,
    maxAge:    Option[Long]     = None,
    domain:    Option[String]   = None,
    path:      Option[String]   = None,
    secure:    Boolean          = false,
    httpOnly:  Boolean          = false,
    extension: Option[String]   = None,
    sameSite:  Option[SameSite] = None
  ) = new HttpCookie(name, value, expires, maxAge, domain, path, secure, httpOnly, extension, sameSite)

  @deprecated("for binary compatibility", since = "10.2.0")
  def apply(
    name:      String,
    value:     String,
    expires:   Option[DateTime],
    maxAge:    Option[Long],
    domain:    Option[String],
    path:      Option[String],
    secure:    Boolean,
    httpOnly:  Boolean,
    extension: Option[String]
  ) = new HttpCookie(name, value, expires, maxAge, domain, path, secure, httpOnly, extension, None)

  @deprecated("for binary compatibility", since = "10.2.0")
  def unapply(cookie: HttpCookie) = Option((
    cookie.name(),
    cookie.value(),
    cookie.expires,
    cookie.maxAge,
    cookie.domain,
    cookie.path,
    cookie.secure(),
    cookie.httpOnly(),
    cookie.extension
  ))

  def createFromPair(
    pair:      HttpCookiePair,
    expires:   Option[DateTime] = None,
    maxAge:    Option[Long]     = None,
    domain:    Option[String]   = None,
    path:      Option[String]   = None,
    secure:    Boolean          = false,
    httpOnly:  Boolean          = false,
    extension: Option[String]   = None,
    sameSite:  Option[SameSite] = None): HttpCookie =
    new HttpCookie(pair.name, pair.value, expires, maxAge, domain, path, secure, httpOnly, extension, sameSite)

  @deprecated("Use createFromPair instead", "10.2.0")
  def fromPair(
    pair:      HttpCookiePair,
    expires:   Option[DateTime] = None,
    maxAge:    Option[Long]     = None,
    domain:    Option[String]   = None,
    path:      Option[String]   = None,
    secure:    Boolean          = false,
    httpOnly:  Boolean          = false,
    extension: Option[String]   = None): HttpCookie =
    new HttpCookie(pair.name, pair.value, expires, maxAge, domain, path, secure, httpOnly, extension, None)

  import akka.http.impl.model.parser.CharacterClasses._

  private[http] def nameChars = tchar
  /**
   * http://tools.ietf.org/html/rfc6265#section-4.1.1
   * US-ASCII characters excluding CTLs, whitespace DQUOTE, comma, semicolon, and backslash
   */
  private[http] val valueChars = CharPredicate('\u0021', '\u0023' to '\u002B', '\u002D' to '\u003A', '\u003C' to '\u005B', '\u005D' to '\u007E')
  private[http] val rawValueChars = CharacterClasses.`cookie-octet-raw`
  private[http] val domainChars = ALPHANUM ++ ".-"
  private[http] val pathOrExtChars = VCHAR ++ ' ' -- ';'
}

/**
 * The Cookie SameSite attribute as defined by <a href="https://tools.ietf.org/html/draft-ietf-httpbis-cookie-same-site-00">RFC6265bis</a>
 * and <a href="https://tools.ietf.org/html/draft-west-cookie-incrementalism-00">Incrementally Better Cookies</a>.
 */
sealed trait SameSite extends Renderable {
  def asJava: jm.headers.SameSite = this match {
    case SameSite.Strict => jm.headers.SameSite.Strict
    case SameSite.Lax    => jm.headers.SameSite.Lax
    case SameSite.None   => jm.headers.SameSite.None
  }

  override private[http] def render[R <: Rendering](r: R): r.type = r ~~ (this match {
    case SameSite.Strict => "Strict"
    case SameSite.Lax    => "Lax"
    case SameSite.None   => "None"
  })
}

object SameSite {

  def apply(s: String): Option[SameSite] = {
    if ("Lax".equalsIgnoreCase(s)) Some(Lax)
    else if ("Strict".equalsIgnoreCase(s)) Some(Strict)
    else if ("None".equalsIgnoreCase(s)) Some(None)
    else Option.empty
  }

  case object Strict extends SameSite
  case object Lax extends SameSite

  // SameSite.None is different from not adding the SameSite attribute in a cookie.
  // - Cookies without a SameSite attribute will be treated as SameSite=Lax.
  // - Cookies for cross-site usage must specify `SameSite=None; Secure` to enable inclusion in third party
  //   context. We are not enforcing `; Secure` when `SameSite=None`, but users should.
  case object None extends SameSite
}
