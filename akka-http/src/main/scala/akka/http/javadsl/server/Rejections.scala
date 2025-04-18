/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.annotation.DoNotInherit
import akka.http.impl.util.JavaMapping
import akka.http.scaladsl.server.ContentNegotiator.Alternative
import akka.http.scaladsl.server._
import akka.http.javadsl.model._
import akka.http.javadsl.model.headers.{ ByteRange, HttpChallenge, HttpEncoding }
import akka.http.scaladsl
import akka.japi.Util
import akka.pattern.CircuitBreakerOpenException

import java.lang.{ Iterable => JIterable }
import java.util.Optional
import java.util.function.{ Function => JFunction }
import java.util.{ List => JList }

import scala.collection.immutable
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/**
 * A rejection encapsulates a specific reason why a Route was not able to handle a request. Rejections are gathered
 * up over the course of a Route evaluation and finally converted to [[akka.http.scaladsl.model.HttpResponse]]s by the
 * `handleRejections` directive, if there was no way for the request to be completed.
 *
 * If providing custom rejections, extend [[CustomRejection]] instead.
 */
trait Rejection

/** To be extended by user-provided custom rejections, such that they may be consumed in either Java or Scala DSLs. */
trait CustomRejection extends akka.http.scaladsl.server.Rejection

/**
 * Rejection created by method filters.
 * Signals that the request was rejected because the HTTP method is unsupported.
 */
@DoNotInherit
trait MethodRejection extends Rejection {
  def supported: HttpMethod
}

/**
 * Rejection created by scheme filters.
 * Signals that the request was rejected because the Uri scheme is unsupported.
 */
@DoNotInherit
trait SchemeRejection extends Rejection {
  def supported: String
}

/**
 * Rejection created by parameter filters.
 * Signals that the request was rejected because a query parameter was not found.
 */
@DoNotInherit
trait MissingQueryParamRejection extends Rejection {
  def parameterName: String
}

/**
 * Rejection created by parameter filters.
 * Signals that the request was rejected because a query parameter value was not equal to required one.
 */
@DoNotInherit
trait InvalidRequiredValueForQueryParamRejection extends Rejection {
  def parameterName: String
  def expectedValue: String
  def actualValue: String
}

/**
 * Rejection created by parameter filters.
 * Signals that the request was rejected because a query parameter could not be interpreted.
 */
@DoNotInherit
trait MalformedQueryParamRejection extends Rejection {
  def parameterName: String
  def errorMsg: String
  def getCause: Optional[Throwable]
}

/**
 * Rejection created by form field filters.
 * Signals that the request was rejected because a form field was not found.
 */
@DoNotInherit
trait MissingFormFieldRejection extends Rejection {
  def fieldName: String
}

/**
 * Rejection created by form field filters.
 * Signals that the request was rejected because a form field could not be interpreted.
 */
@DoNotInherit
trait MalformedFormFieldRejection extends Rejection {
  def fieldName: String
  def errorMsg: String
  def getCause: Optional[Throwable]
}

/**
 * Rejection created by header directives.
 * Signals that the request was rejected because a required header could not be found.
 */
@DoNotInherit
trait MissingHeaderRejection extends Rejection {
  def headerName: String
}

/**
 * Rejection created by attribute directives.
 * Signals that the request was rejected because a required request attribute could not be found.
 */
@DoNotInherit
trait MissingAttributeRejection[T] extends Rejection {
  def key: AttributeKey[T]
}

/**
 * Rejection created by header directives.
 * Signals that the request was rejected because a header value is malformed.
 */
@DoNotInherit
trait MalformedHeaderRejection extends Rejection {
  def headerName: String
  def errorMsg: String
  def getCause: Optional[Throwable]
}

/**
 * Rejection created by [[akka.http.scaladsl.server.directives.HeaderDirectives.checkSameOrigin]].
 * Signals that the request was rejected because `Origin` header value is invalid.
 */
@DoNotInherit
trait InvalidOriginRejection extends Rejection {
  def getAllowedOrigins: java.util.List[akka.http.javadsl.model.headers.HttpOrigin]
}

/**
 * Rejection created by unmarshallers.
 * Signals that the request was rejected because the requests content-type is unsupported.
 */
@DoNotInherit
trait UnsupportedRequestContentTypeRejection extends Rejection {
  def getSupported: java.util.Set[akka.http.javadsl.model.ContentTypeRange]
}

/**
 * Rejection created by decoding filters.
 * Signals that the request was rejected because the requests content encoding is unsupported.
 */
@DoNotInherit
trait UnsupportedRequestEncodingRejection extends Rejection {
  def supported: HttpEncoding
}

/**
 * Rejection created by range directives.
 * Signals that the request was rejected because the requests contains only unsatisfiable ByteRanges.
 * The actualEntityLength gives the client a hint to create satisfiable ByteRanges.
 */
@DoNotInherit
trait UnsatisfiableRangeRejection extends Rejection {
  def getUnsatisfiableRanges: JIterable[ByteRange]
  def actualEntityLength: Long
}

/**
 * Rejection created by range directives.
 * Signals that the request contains too many ranges. An irregular high number of ranges
 * indicates a broken client or a denial of service attack.
 */
@DoNotInherit
trait TooManyRangesRejection extends Rejection {
  def maxRanges: Int
}

/**
 * Rejection created by unmarshallers.
 * Signals that the request was rejected because unmarshalling failed with an error that wasn't
 * an `IllegalArgumentException`. Usually that means that the request content was not of the expected format.
 * Note that semantic issues with the request content (e.g. because some parameter was out of range)
 * will usually trigger a `ValidationRejection` instead.
 */
@DoNotInherit
trait MalformedRequestContentRejection extends Rejection {
  def message: String
  def getCause: Throwable
}

/**
 * Rejection created by unmarshallers.
 * Signals that the request was rejected because an message body entity was expected but not supplied.
 */
@DoNotInherit
abstract class RequestEntityExpectedRejection extends Rejection
object RequestEntityExpectedRejection {
  def get: RequestEntityExpectedRejection = scaladsl.server.RequestEntityExpectedRejection
}

/**
 * Rejection created by marshallers.
 * Signals that the request was rejected because the service is not capable of producing a response entity whose
 * content type is accepted by the client
 */
@DoNotInherit
trait UnacceptedResponseContentTypeRejection extends Rejection {
  def supported: immutable.Set[ContentNegotiator.Alternative]
}

/**
 * Rejection created by encoding filters.
 * Signals that the request was rejected because the service is not capable of producing a response entity whose
 * content encoding is accepted by the client
 */
@DoNotInherit
trait UnacceptedResponseEncodingRejection extends Rejection {
  def getSupported: java.util.Set[HttpEncoding]
}
object UnacceptedResponseEncodingRejection {
  def create(supported: HttpEncoding): UnacceptedResponseEncodingRejection =
    scaladsl.server.UnacceptedResponseEncodingRejection(JavaMapping.toScala(supported))
}

/**
 * Rejection created by the various [[akka.http.javadsl.server.directives.SecurityDirectives]].
 * Signals that the request was rejected because the user could not be authenticated. The reason for the rejection is
 * specified in the cause.
 */
@DoNotInherit
trait AuthenticationFailedRejection extends Rejection {
  def cause: AuthenticationFailedRejection.Cause
  def challenge: HttpChallenge
}

object AuthenticationFailedRejection {
  /**
   * Signals the cause of the failed authentication.
   */
  trait Cause

  /**
   * Signals the cause of the rejecting was that the user could not be authenticated, because the `WWW-Authenticate`
   * header was not supplied.
   */
  trait CredentialsMissing extends Cause

  /**
   * Signals the cause of the rejecting was that the user could not be authenticated, because the supplied credentials
   * are invalid.
   */
  trait CredentialsRejected extends Cause
}

/**
 * Rejection created by the 'authorize' directive.
 * Signals that the request was rejected because the user is not authorized.
 */
@DoNotInherit
trait AuthorizationFailedRejection extends Rejection
object AuthorizationFailedRejection {
  def get = scaladsl.server.AuthorizationFailedRejection
}

/**
 * Rejection created by the `cookie` directive.
 * Signals that the request was rejected because a cookie was not found.
 */
@DoNotInherit
trait MissingCookieRejection extends Rejection {
  def cookieName: String
}

/**
 * Rejection created when a websocket request was expected but none was found.
 */
@DoNotInherit
trait ExpectedWebSocketRequestRejection extends Rejection
object ExpectedWebSocketRequestRejection {
  def get: ExpectedWebSocketRequestRejection = scaladsl.server.ExpectedWebSocketRequestRejection
}

/**
 * Rejection created when a websocket request was not handled because none of the given subprotocols
 * was supported.
 */
@DoNotInherit
trait UnsupportedWebSocketSubprotocolRejection extends Rejection {
  def supportedProtocol: String
}

/**
 * Rejection created by the `validation` directive as well as for `IllegalArgumentExceptions`
 * thrown by domain model constructors (e.g. via `require`).
 * It signals that an expected value was semantically invalid.
 */
@DoNotInherit
trait ValidationRejection extends Rejection {
  def message: String
  def getCause: Optional[Throwable]
}

/**
 * Rejection created by the `onCompleteWithBreaker` directive.
 * Signals that the request was rejected because the supplied circuit breaker is open and requests are failing fast.
 */
@DoNotInherit
trait CircuitBreakerOpenRejection extends Rejection {
  def cause: CircuitBreakerOpenException
}

/**
 * A special Rejection that serves as a container for a transformation function on rejections.
 * It is used by some directives to "cancel" rejections that are added by later directives of a similar type.
 *
 * Consider this route structure for example:
 *
 *     put { reject(ValidationRejection("no") } ~ get { ... }
 *
 * If this structure is applied to a PUT request the list of rejections coming back contains three elements:
 *
 * 1. A ValidationRejection
 * 2. A MethodRejection
 * 3. A TransformationRejection holding a function filtering out the MethodRejection
 *
 * so that in the end the RejectionHandler will only see one rejection (the ValidationRejection), because the
 * MethodRejection added by the `get` directive is canceled by the `put` directive (since the HTTP method
 * did indeed match eventually).
 */
@DoNotInherit
trait TransformationRejection extends Rejection {
  def getTransform: JFunction[JIterable[Rejection], JIterable[Rejection]]
}

/**
 * A Throwable wrapping a Rejection.
 * Can be used for marshalling `Future[T]` or `Try[T]` instances, whose failure side is supposed to trigger a route
 * rejection rather than an Exception that is handled by the nearest ExceptionHandler.
 * (Custom marshallers can of course use it as well.)
 */
trait RejectionError extends RuntimeException {
  def rejection: Rejection
}

object Rejections {
  import akka.http.scaladsl.{ server => s }
  import JavaMapping.Implicits._
  import RoutingJavaMapping._

  def method(supported: HttpMethod): MethodRejection =
    s.MethodRejection(JavaMapping.toScala(supported))

  def scheme(supported: String): SchemeRejection =
    s.SchemeRejection(supported)

  def missingQueryParam(parameterName: String): MissingQueryParamRejection =
    s.MissingQueryParamRejection(parameterName)

  def invalidRequiredValueForQueryParam(parameterName: String, requiredValue: String, actualValue: String): InvalidRequiredValueForQueryParamRejection =
    s.InvalidRequiredValueForQueryParamRejection(parameterName, requiredValue, actualValue)

  def malformedQueryParam(parameterName: String, errorMsg: String): MalformedQueryParamRejection =
    s.MalformedQueryParamRejection(parameterName, errorMsg)
  def malformedQueryParam(parameterName: String, errorMsg: String, cause: Optional[Throwable]): MalformedQueryParamRejection =
    s.MalformedQueryParamRejection(parameterName, errorMsg, cause.toScala)

  def missingFormField(fieldName: String): MissingFormFieldRejection =
    s.MissingFormFieldRejection(fieldName)

  def malformedFormField(fieldName: String, errorMsg: String): MalformedFormFieldRejection =
    s.MalformedFormFieldRejection(fieldName, errorMsg)
  def malformedFormField(fieldName: String, errorMsg: String, cause: Optional[Throwable]): s.MalformedFormFieldRejection =
    s.MalformedFormFieldRejection(fieldName, errorMsg, cause.toScala)

  def missingHeader(headerName: String): MissingHeaderRejection =
    s.MissingHeaderRejection(headerName)

  def malformedHeader(headerName: String, errorMsg: String): MalformedHeaderRejection =
    s.MalformedHeaderRejection(headerName, errorMsg)
  def malformedHeader(headerName: String, errorMsg: String, cause: Optional[Throwable]): s.MalformedHeaderRejection =
    s.MalformedHeaderRejection(headerName, errorMsg, cause.toScala)

  def unsupportedRequestContentType(
    supported:   java.lang.Iterable[MediaType],
    contentType: Optional[ContentType]): UnsupportedRequestContentTypeRejection =
    s.UnsupportedRequestContentTypeRejection(
      supported = supported.asScala.map((m: MediaType) => scaladsl.model.ContentTypeRange(m.asScala)).toSet,
      contentType = contentType.toScala.map((c: ContentType) => c.asScala))

  // for backwards compatibility
  def unsupportedRequestContentType(supported: java.lang.Iterable[MediaType]): UnsupportedRequestContentTypeRejection =
    s.UnsupportedRequestContentTypeRejection(
      supported = supported.asScala.map((m: MediaType) => scaladsl.model.ContentTypeRange(m.asScala)).toSet,
      contentType = None)

  def unsupportedRequestEncoding(supported: HttpEncoding): UnsupportedRequestEncodingRejection =
    s.UnsupportedRequestEncodingRejection(supported.asScala)

  def unsatisfiableRange(unsatisfiableRanges: java.lang.Iterable[ByteRange], actualEntityLength: Long) =
    UnsatisfiableRangeRejection(Util.immutableSeq(unsatisfiableRanges).map(_.asScala), actualEntityLength)

  def tooManyRanges(maxRanges: Int) = TooManyRangesRejection(maxRanges)

  def malformedRequestContent(message: String, cause: Throwable) =
    MalformedRequestContentRejection(message, cause)

  def requestEntityExpected = RequestEntityExpectedRejection

  def unacceptedResponseContentType(
    supportedContentTypes: java.lang.Iterable[ContentType],
    supportedMediaTypes:   java.lang.Iterable[MediaType]): UnacceptedResponseContentTypeRejection = {
    val s1: Set[Alternative] = supportedContentTypes.asScala.map((c: ContentType) => c.asScala).map(ct => ContentNegotiator.Alternative(ct)).toSet
    val s2: Set[Alternative] = supportedMediaTypes.asScala.map((m: MediaType) => m.asScala).map(mt => ContentNegotiator.Alternative(mt)).toSet
    s.UnacceptedResponseContentTypeRejection(s1 ++ s2)
  }

  def unacceptedResponseEncoding(supported: HttpEncoding) =
    s.UnacceptedResponseEncodingRejection(supported.asScala)
  def unacceptedResponseEncoding(supported: java.lang.Iterable[HttpEncoding]) =
    s.UnacceptedResponseEncodingRejection(supported.asScala.map((h: HttpEncoding) => h.asScala).toSet)

  def authenticationCredentialsMissing(challenge: HttpChallenge): AuthenticationFailedRejection =
    s.AuthenticationFailedRejection(s.AuthenticationFailedRejection.CredentialsMissing, challenge.asScala)
  def authenticationCredentialsRejected(challenge: HttpChallenge): AuthenticationFailedRejection =
    s.AuthenticationFailedRejection(s.AuthenticationFailedRejection.CredentialsRejected, challenge.asScala)

  def authorizationFailed =
    s.AuthorizationFailedRejection

  def missingCookie(cookieName: String) =
    s.MissingCookieRejection(cookieName)

  def expectedWebSocketRequest =
    s.ExpectedWebSocketRequestRejection

  def validationRejection(message: String) =
    s.ValidationRejection(message)
  def validationRejection(message: String, cause: Optional[Throwable]) =
    s.ValidationRejection(message, cause.toScala)

  def transformationRejection(f: java.util.function.Function[java.util.List[Rejection], java.util.List[Rejection]]) =
    s.TransformationRejection(rejections => f.apply(rejections.map(_.asJava).asJava).asScala.toVector.map(_.asScala)) // TODO this is maddness

  def rejectionError(rejection: Rejection) =
    s.RejectionError(convertToScala(rejection))
}

/**
 * Not for user extension
 */
@DoNotInherit
trait CorsRejection extends Rejection {
  def description: String
}

/**
 * Not for user extension
 */
@DoNotInherit
trait TlsClientUnverifiedRejection extends Rejection {
  def description: String
}

/**
 * Not for user extension
 */
@DoNotInherit
trait TlsClientIdentityRejection extends Rejection {
  def description: String
  def requiredExpression: String
  def getCertificateCN: Optional[String]
  def getCertificateSANs: JList[String]
}
