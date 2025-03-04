/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.reflect.ClassTag
import scala.concurrent.Future
import akka.http.impl.util._
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.AuthenticationFailedRejection.{ CredentialsRejected, CredentialsMissing }

import scala.util.Success

/**
 * Provides directives for securing an inner route using the standard Http authentication headers [[`WWW-Authenticate`]]
 * and [[Authorization]]. Most prominently, HTTP Basic authentication and OAuth 2.0 Authorization Framework
 * as defined in RFC 2617 and RFC 6750 respectively.
 *
 * See: <a href="https://www.ietf.org/rfc/rfc2617.txt">RFC 2617</a>.
 * See: <a href="https://www.ietf.org/rfc/rfc6750.txt">RFC 6750</a>.
 *
 * @groupname security Security directives
 * @groupprio security 220
 */
trait SecurityDirectives {
  import BasicDirectives._
  import HeaderDirectives._
  import FutureDirectives._
  import RouteDirectives._

  //#authentication-result
  /**
   * The result of an HTTP authentication attempt is either the user object or
   * an HttpChallenge to present to the browser.
   *
   * @group security
   */
  type AuthenticationResult[+T] = Either[HttpChallenge, T]
  //#authentication-result

  //#authenticator
  /**
   * @group security
   */
  type Authenticator[T] = Credentials => Option[T]
  //#authenticator
  //#async-authenticator
  /**
   * @group security
   */
  type AsyncAuthenticator[T] = Credentials => Future[Option[T]]
  //#async-authenticator
  //#authenticator-pf
  /**
   * @group security
   */
  type AuthenticatorPF[T] = PartialFunction[Credentials, T]
  //#authenticator-pf
  //#async-authenticator-pf
  /**
   * @group security
   */
  type AsyncAuthenticatorPF[T] = PartialFunction[Credentials, Future[T]]
  //#async-authenticator-pf

  /**
   * Extracts the potentially present [[HttpCredentials]] provided with the request's [[Authorization]] header.
   *
   * @group security
   */
  def extractCredentials: Directive1[Option[HttpCredentials]] =
    optionalHeaderValueByType(Authorization).map(_.map(_.credentials))

  /**
   * Wraps the inner route with Http Basic authentication support using a given `Authenticator[T]`.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateBasic[T](realm: String, authenticator: Authenticator[T]): AuthenticationDirective[T] =
    authenticateBasicAsync(realm, cred => FastFuture.successful(authenticator(cred)))

  /**
   * Wraps the inner route with Http Basic authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateBasicAsync[T](realm: String, authenticator: AsyncAuthenticator[T]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ec =>
      authenticateOrRejectWithChallenge[BasicHttpCredentials, T] { cred =>
        authenticator(Credentials(cred)).fast.map {
          case Some(t) => AuthenticationResult.success(t)
          case None    => AuthenticationResult.failWithChallenge(HttpChallenges.basic(realm))
        }
      }
    }

  /**
   * A directive that wraps the inner route with Http Basic authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateBasicPF[T](realm: String, authenticator: AuthenticatorPF[T]): AuthenticationDirective[T] =
    authenticateBasic(realm, authenticator.lift)

  /**
   * A directive that wraps the inner route with Http Basic authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateBasicPFAsync[T](realm: String, authenticator: AsyncAuthenticatorPF[T]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ec =>
      authenticateBasicAsync(realm, credentials =>
        if (authenticator isDefinedAt credentials) authenticator(credentials).fast.map(Some(_))
        else FastFuture.successful(None))
    }

  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateOAuth2[T](realm: String, authenticator: Authenticator[T]): AuthenticationDirective[T] =
    authenticateOAuth2Async(realm, cred => FastFuture.successful(authenticator(cred)))

  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateOAuth2Async[T](realm: String, authenticator: AsyncAuthenticator[T]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ec =>
      def extractAccessTokenParameterAsBearerToken = {
        import akka.http.scaladsl.server.Directives._
        parameter("access_token".optional).map(_.map(OAuth2BearerToken))
      }
      val extractCreds: Directive1[Option[OAuth2BearerToken]] =
        extractCredentials.flatMap {
          case Some(c: OAuth2BearerToken) => provide(Some(c))
          case _                          => extractAccessTokenParameterAsBearerToken
        }

      extractCredentialsAndAuthenticateOrRejectWithChallenge[OAuth2BearerToken, T](extractCreds, { cred =>
        authenticator(Credentials(cred)).fast.map {
          case Some(t) => AuthenticationResult.success(t)
          case None    => AuthenticationResult.failWithChallenge(HttpChallenges.oAuth2(realm))
        }
      })
    }

  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateOAuth2PF[T](realm: String, authenticator: AuthenticatorPF[T]): AuthenticationDirective[T] =
    authenticateOAuth2(realm, authenticator.lift)

  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateOAuth2PFAsync[T](realm: String, authenticator: AsyncAuthenticatorPF[T]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ec =>
      authenticateOAuth2Async(realm, credentials =>
        if (authenticator isDefinedAt credentials) authenticator(credentials).fast.map(Some(_))
        else FastFuture.successful(None))
    }

  /**
   * Lifts an authenticator function into a directive. The authenticator function gets passed in credentials from the
   * [[Authorization]] header of the request. If the function returns `Right(user)` the user object is provided
   * to the inner route. If the function returns `Left(challenge)` the request is rejected with an
   * [[AuthenticationFailedRejection]] that contains this challenge to be added to the response.
   *
   * You can supply a directive to extract the credentials (to support alternative ways of providing credentials).
   *
   * @group security
   */
  private def extractCredentialsAndAuthenticateOrRejectWithChallenge[C <: HttpCredentials, T](
    extractCredentials: Directive1[Option[C]],
    authenticator:      Option[C] => Future[AuthenticationResult[T]]): AuthenticationDirective[T] =
    extractCredentials.flatMap { cred =>
      onSuccess(authenticator(cred)).flatMap {
        case Right(user) => provide(user)
        case Left(challenge) =>
          val cause = if (cred.isEmpty) CredentialsMissing else CredentialsRejected
          reject(AuthenticationFailedRejection(cause, challenge)): Directive1[T]
      }
    }

  /**
   * Lifts an authenticator function into a directive. The authenticator function gets passed in credentials from the
   * [[Authorization]] header of the request. If the function returns `Right(user)` the user object is provided
   * to the inner route. If the function returns `Left(challenge)` the request is rejected with an
   * [[AuthenticationFailedRejection]] that contains this challenge to be added to the response.
   *
   * @group security
   */
  def authenticateOrRejectWithChallenge[T](authenticator: Option[HttpCredentials] => Future[AuthenticationResult[T]]): AuthenticationDirective[T] =
    extractCredentialsAndAuthenticateOrRejectWithChallenge(extractCredentials, authenticator)

  /**
   * Lifts an authenticator function into a directive. Same as `authenticateOrRejectWithChallenge`
   * but only applies the authenticator function with a certain type of credentials.
   *
   * @group security
   */
  def authenticateOrRejectWithChallenge[C <: HttpCredentials: ClassTag, T](
    authenticator: Option[C] => Future[AuthenticationResult[T]]): AuthenticationDirective[T] =
    extractCredentialsAndAuthenticateOrRejectWithChallenge(extractCredentials.map(_ collect { case c: C => c }), authenticator)

  /**
   * Applies the given authorization check to the request.
   * If the check fails the route is rejected with an [[AuthorizationFailedRejection]].
   *
   * @group security
   */
  def authorize(check: => Boolean): Directive0 = authorize(_ => check)

  /**
   * Applies the given authorization check to the request.
   * If the check fails the route is rejected with an [[AuthorizationFailedRejection]].
   *
   * @group security
   */
  def authorize(check: RequestContext => Boolean): Directive0 =
    authorizeAsync(ctx => Future.successful(check(ctx)))

  /**
   * Asynchronous version of [[authorize]].
   * If the [[Future]] fails or is completed with `false`
   * authorization fails and the route is rejected with an [[AuthorizationFailedRejection]].
   *
   * @group security
   */
  def authorizeAsync(check: => Future[Boolean]): Directive0 =
    authorizeAsync(ctx => check)

  /**
   * Asynchronous version of [[authorize]].
   * If the [[Future]] fails or is completed with `false`
   * authorization fails and the route is rejected with an [[AuthorizationFailedRejection]].
   *
   * @group security
   */
  def authorizeAsync(check: RequestContext => Future[Boolean]): Directive0 =
    extract(check).flatMap[Unit] { fa =>
      onComplete(fa).flatMap {
        case Success(true) => pass
        case _             => reject(AuthorizationFailedRejection)
      }
    }
}

object SecurityDirectives extends SecurityDirectives

/**
 * Represents authentication credentials supplied with a request. Credentials can either be
 * [[Credentials.Missing]] or can be [[Credentials.Provided]] in which case an identifier is
 * supplied and a function to check the known secret against the provided one in a secure fashion.
 */
sealed trait Credentials
object Credentials {
  case object Missing extends Credentials
  abstract case class Provided(identifier: String) extends Credentials {

    /**
     * First applies the passed in `hasher` function to the received secret part of the Credentials
     * and then safely compares the passed in `secret` with the hashed received secret.
     * This method can be used if the secret is not stored in plain text.
     * Use of this method instead of manual String equality testing is recommended in order to guard against timing attacks.
     *
     * See also [[EnhancedString#secure_==]], for more information.
     */
    def verify(secret: String, hasher: String => String): Boolean

    /**
     * Safely compares the passed in `secret` with the received secret part of the Credentials.
     * Use of this method instead of manual String equality testing is recommended in order to guard against timing attacks.
     *
     * See also [[EnhancedString#secure_==]], for more information.
     */
    def verify(secret: String): Boolean = verify(secret, x => x)

    /**
     * Compares with custom 'verifier' the received secret part of the Credentials.
     * Use of this method only if custom String equality testing is required, not recommended.
     */
    def provideVerify(verifier: String => Boolean): Boolean

    /**
     * Compares with custom 'verifier' and the passed secret with the received secret part of the Credentials.
     * Use of this method only if custom String equality testing is required, not recommended.
     */
    def provideVerify(secret: String, verifier: (String, String) => Boolean): Boolean = provideVerify(verifier.curried(secret))
  }

  def apply(cred: Option[HttpCredentials]): Credentials = {
    cred match {
      case Some(BasicHttpCredentials(username, receivedSecret)) =>
        new Credentials.Provided(username) {
          def verify(secret: String, hasher: String => String): Boolean = secret secure_== hasher(receivedSecret)
          def provideVerify(verifier: String => Boolean): Boolean = verifier(receivedSecret)
        }
      case Some(OAuth2BearerToken(token)) =>
        new Credentials.Provided(token) {
          def verify(secret: String, hasher: String => String): Boolean = secret secure_== hasher(token)
          def provideVerify(verifier: String => Boolean): Boolean = verifier(token)
        }
      case Some(c) =>
        throw new UnsupportedOperationException(s"Credentials does not support scheme '${c.scheme}'.")
      case None => Credentials.Missing
    }
  }
}

import SecurityDirectives._

object AuthenticationResult {
  def success[T](user: T): AuthenticationResult[T] = Right(user)
  def failWithChallenge(challenge: HttpChallenge): AuthenticationResult[Nothing] = Left(challenge)
}

trait AuthenticationDirective[T] extends Directive1[T] {
  import BasicDirectives._
  import RouteDirectives._

  /**
   * Returns a copy of this [[AuthenticationDirective]] that will provide `Some(user)` if credentials
   * were supplied and otherwise `None`.
   */
  def optional: Directive1[Option[T]] =
    this.map(Some(_): Option[T]) recover {
      case AuthenticationFailedRejection(CredentialsMissing, _) +: _ => provide(None)
      case rejs => reject(rejs: _*)
    }

  /**
   * Returns a copy of this [[AuthenticationDirective]] that uses the given object as the
   * anonymous user which will be used if no credentials were supplied in the request.
   */
  def withAnonymousUser(anonymous: T): Directive1[T] = optional map (_ getOrElse anonymous)
}
object AuthenticationDirective {
  implicit def apply[T](other: Directive1[T]): AuthenticationDirective[T] =
    new AuthenticationDirective[T] { def tapply(inner: Tuple1[T] => Route) = other.tapply(inner) }
}
