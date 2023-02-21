/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import java.util.Locale

import akka.http.impl.util._
import akka.http.javadsl.{ model => jm }
import akka.http.scaladsl.model.RequestEntityAcceptance._
import akka.util.ConstantFun

sealed trait RequestEntityAcceptance extends jm.RequestEntityAcceptance {
  def isEntityAccepted: Boolean
}
object RequestEntityAcceptance {
  case object Expected extends RequestEntityAcceptance {
    override def isEntityAccepted: Boolean = true
  }
  case object Tolerated extends RequestEntityAcceptance {
    override def isEntityAccepted: Boolean = true
  }
  case object Disallowed extends RequestEntityAcceptance {
    override def isEntityAccepted: Boolean = false
  }
}

/**
 * The method of an HTTP request.
 * @param isSafe true if the resource should not be altered on the server
 * @param isIdempotent true if requests can be safely (& automatically) repeated
 * @param requestEntityAcceptance Expected if meaning of request entities is properly defined
 * @param contentLengthAllowed Function defining whether the method-statuscode combination should output the Content-Length header.
 */
final case class HttpMethod private[http] (
  override val value:       String,
  isSafe:                   Boolean,
  isIdempotent:             Boolean,
  requestEntityAcceptance:  RequestEntityAcceptance,
  val contentLengthAllowed: StatusCode => Boolean) extends jm.HttpMethod with SingletonValueRenderable {
  override def isEntityAccepted: Boolean = requestEntityAcceptance.isEntityAccepted
  override def toString: String = s"HttpMethod($value)"
}

object HttpMethod {

  // the allowsEntity condition was used to determine what responses provided the Content-Length header, before #4213 was fixed
  private def oldContentLengthCondition(status: StatusCode) = status.allowsEntity

  /**
   * Create a custom method type.
   * @deprecated The created method will compute the presence of Content-Length headers based on deprecated logic (before issue #4213).
   */
  @Deprecated
  def custom(name: String, safe: Boolean, idempotent: Boolean, requestEntityAcceptance: RequestEntityAcceptance): HttpMethod = {
    require(name.nonEmpty, "value must be non-empty")
    require(!safe || idempotent, "An HTTP method cannot be safe without being idempotent")
    apply(name, safe, idempotent, requestEntityAcceptance, oldContentLengthCondition)
  }

  /**
   * Create a custom method type.
   */
  def custom(name: String, safe: Boolean, idempotent: Boolean, requestEntityAcceptance: RequestEntityAcceptance, contentLengthAllowed: Boolean): HttpMethod = {
    require(name.nonEmpty, "value must be non-empty")
    require(!safe || idempotent, "An HTTP method cannot be safe without being idempotent")
    // use constant functions so custom HttpMethod instances are equal (case class equality) when built with the same parameters
    apply(name, safe, idempotent, requestEntityAcceptance, if (contentLengthAllowed) ConstantFun.anyToTrue else ConstantFun.anyToFalse)
  }

  /**
   * Creates a custom method by name and assumes properties conservatively to be
   * safe = false, idempotent = false, requestEntityAcceptance = Expected and contentLengthAllowed always true.
   */
  def custom(name: String): HttpMethod = custom(name, safe = false, idempotent = false, requestEntityAcceptance = Expected, contentLengthAllowed = true)
}

object HttpMethods extends ObjectRegistry[String, HttpMethod] {
  private def register(method: HttpMethod): HttpMethod = register(method.value, method)

  // define requirements for content-length according to https://httpwg.org/specs/rfc9110.html#field.content-length
  // for CONNECT it is explicitly not allowed in the 2xx (Successful) range
  private def contentLengthAllowedForConnect(forStatus: StatusCode): Boolean = forStatus.intValue < 200 || forStatus.intValue >= 300
  // for HEAD it is technically allowed, but must match the content-length of hypothetical GET request, so can not be anticipated
  private def contentLengthAllowedForHead(forStatus: StatusCode): Boolean = false
  // for other methods there are common rules:
  // - for 1xx (Informational) or 204 (No Content) it is explicitly not allowed
  // - for 304 (Not Modified) it must match the content-length of hypothetical 200-accepted request, so can not be anticipated
  private def contentLengthAllowedCommon(forStatus: StatusCode): Boolean = {
    val code = forStatus.intValue
    !(code < 200 || code == 204 || code == 304)
  }

  // format: OFF
  val CONNECT = register(HttpMethod("CONNECT", isSafe = false, isIdempotent = false, requestEntityAcceptance = Disallowed, contentLengthAllowed = contentLengthAllowedForConnect))
  val DELETE  = register(HttpMethod("DELETE" , isSafe = false, isIdempotent = true , requestEntityAcceptance = Tolerated, contentLengthAllowed = contentLengthAllowedCommon))
  val GET     = register(HttpMethod("GET"    , isSafe = true , isIdempotent = true , requestEntityAcceptance = Tolerated, contentLengthAllowed = contentLengthAllowedCommon))
  val HEAD    = register(HttpMethod("HEAD"   , isSafe = true , isIdempotent = true , requestEntityAcceptance = Disallowed, contentLengthAllowed = contentLengthAllowedForHead))
  val OPTIONS = register(HttpMethod("OPTIONS", isSafe = true , isIdempotent = true , requestEntityAcceptance = Expected, contentLengthAllowed = contentLengthAllowedCommon))
  val PATCH   = register(HttpMethod("PATCH"  , isSafe = false, isIdempotent = false, requestEntityAcceptance = Expected, contentLengthAllowed = contentLengthAllowedCommon))
  val POST    = register(HttpMethod("POST"   , isSafe = false, isIdempotent = false, requestEntityAcceptance = Expected, contentLengthAllowed = contentLengthAllowedCommon))
  val PUT     = register(HttpMethod("PUT"    , isSafe = false, isIdempotent = true , requestEntityAcceptance = Expected, contentLengthAllowed = contentLengthAllowedCommon))
  val TRACE   = register(HttpMethod("TRACE"  , isSafe = true , isIdempotent = true , requestEntityAcceptance = Disallowed, contentLengthAllowed = contentLengthAllowedCommon))
  // format: ON

  override def getForKeyCaseInsensitive(key: String)(implicit conv: String <:< String): Option[HttpMethod] =
    getForKey(conv(key.toUpperCase(Locale.ROOT)))
}
