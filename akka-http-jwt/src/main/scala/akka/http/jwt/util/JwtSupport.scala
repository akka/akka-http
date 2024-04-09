/*
 * Copyright 2022 Lightbend Inc.
 */

package akka.http.jwt.util

import akka.http.scaladsl.server.JwtRejection

import java.io.File
import java.security.KeyPair
import java.util.Base64
import com.typesafe.config.Config

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import pdi.jwt.{ JwtAlgorithm, JwtOptions, algorithms }
import spray.json.{ JsObject, JsString }

import scala.util.{ Failure, Success, Try }
import scala.jdk.CollectionConverters._

trait JwtSupport {

  def canValidate: Boolean

  def validate(token: String): Either[Exception, JsObject]

}

object JwtSupport {

  private val NoValidationOptions = JwtOptions.DEFAULT.copy(signature = false, expiration = false, notBefore = false)

  def fromConfig(config: Config): JwtSupport = {
    val jwtConfig = config.getConfig("akka.http.jwt")
    val devSecret = if (jwtConfig.getBoolean("dev")) {
      Some(JwtSecret("dev", Some(jwtConfig.getString("dev-issuer")).filter(_.nonEmpty), JwtNoneAlgorithmSecret))
    } else None

    val secrets = jwtConfig
      .getConfigList("secrets")
      .asScala
      .map { secretConfig =>
        val keyId = secretConfig.getString("key-id")
        val algorithmSecret: JwtAlgorithmSecret = secretConfig.getString("algorithm") match {
          case "none" => JwtNoneAlgorithmSecret
          case alg =>
            val algorithm = JwtAlgorithm.fromString(alg)
            if (secretConfig.hasPath("secret-dir")) {
              val dir = new File(secretConfig.getString("secret-dir"))
              if (!dir.exists() || !dir.isDirectory) {
                throw new RuntimeException(
                  s"JWT secret dir for secret <$keyId> does not exist or is not a directory: ${dir.getAbsolutePath}")
              }
              JwtKeyLoader.loadKey(keyId, algorithm, dir)
            } else if (secretConfig.hasPath("secret")) {
              algorithm match {
                case symmetric: algorithms.JwtHmacAlgorithm =>
                  val base64Secret = secretConfig.getString("secret")
                  val secretBytes = Base64.getDecoder.decode(base64Secret)
                  JwtSymmetricAlgorithmSecret(symmetric, new SecretKeySpec(secretBytes, algorithm.fullName))
                case _ => // FIXME exception
                  throw new RuntimeException("Jwt.secretLiteralNotSupportedWithAsymmetricAlgorithm(keyId, algorithm.name)")
              }
            } else { // FIXME exception
              throw new RuntimeException("Jwt.noSecretSpecified(keyId)")
            }
        }
        val issuer =
          if (secretConfig.hasPath("issuer")) Some(secretConfig.getString("issuer")).filter(_.nonEmpty) else None
        JwtSecret(keyId, issuer, algorithmSecret)
      }
      .toList

    new DefaultJwtSupport(secrets ++ devSecret)
  }

  /**
   * @param secrets Order of this list represents priority when selecting secrets for signing and validation.
   */
  final class DefaultJwtSupport(secrets: List[JwtSecret]) extends JwtSupport {
    private val validatingSecrets = secrets.filter(_.secret.canValidate)
    private val validatingSecretsByIssuer = validatingSecrets
      .groupBy(_.issuer)
      .collect {
        case (Some(issuer), s) =>
          issuer -> s
      }

    override def canValidate: Boolean = validatingSecrets.nonEmpty

    override def validate(token: String): Either[Exception, JsObject] = {
      // First, decode without validating so we can get information like key id and issuer
      JwtSprayJson.decodeAll(token, NoValidationOptions) match {
        case Success((header, claim, _)) =>
          // If the claim has an issuer, and there are secrets with that issuers name, then restrict to only using
          // those issuers.
          val issuerBasedValidators = claim.issuer match {
            case Some(issuer) =>
              validatingSecretsByIssuer.get(issuer) match {
                case Some(secrets) => secrets
                case None          => Nil
                //FIXME No secrets for that issuer, just use all secrets
                //validatingSecrets
              }
            case None => validatingSecrets
          }
          issuerBasedValidators match {
            case single :: Nil =>
              // We have a single validating secret, just use that and hope for the best.
              validateToken(token, single)
            case _ =>
              // Filter out the secrets that can't be used for this tokens algorithm
              val validForAlgorithm = header.algorithm match {
                case Some(alg) =>
                  issuerBasedValidators.filter(_.secret.canValidateAlgorithm(alg))
                // None either means there was no algorithm specified, or was "none". Assume it was "none".
                case None =>
                  issuerBasedValidators.filter(_.secret == JwtNoneAlgorithmSecret)
              }
              validForAlgorithm match {
                case Nil =>
                  Left( //FIXME exception
                    new RuntimeException("Failed to verify JWT token due to unsupported algorithm"))
                case single :: Nil =>
                  // Now we have a single secret, use that and hope for the best.
                  validateToken(token, single)
                case _ =>
                  // Let's match by keyid
                  header.keyId match {
                    case None =>
                      // No keyId, just use the first one
                      validateToken(token, validForAlgorithm.head)
                    case Some(keyId) =>
                      // Matching against all validators for this issuer since key ids should be unique.
                      issuerBasedValidators.find(_.keyId == keyId) match {
                        case Some(matching) => validateToken(token, matching)
                        case None =>
                          Left( //FIXME exception
                            new RuntimeException("Failed to verify JWT token due to unknown key id"))
                      }
                  }
              }
          }
        case Failure(e) => //FIXME exception
          Left(new RuntimeException(s"Failed to parse JWT token: ${e.getMessage}"))
      }
    }

    private def validateToken(token: String, secret: JwtSecret): Either[Exception, JsObject] = {
      secret.secret.validate(token) match {
        case Success(value) => Right(value)
        case Failure(e) =>
          Left(new RuntimeException(s"JWT token validation failed: ${e.getMessage}")) // FIXME
      }
    }
  }

  sealed trait JwtAlgorithmSecret {
    def canValidateAlgorithm(alg: JwtAlgorithm): Boolean
    def canValidate: Boolean
    def validate(token: String): Try[JsObject]
    def algorithmName: String
  }

  case class JwtAsymmetricAlgorithmSecret(algorithm: algorithms.JwtAsymmetricAlgorithm, keyPair: KeyPair)
    extends JwtAlgorithmSecret {
    override def canValidateAlgorithm(alg: JwtAlgorithm): Boolean = {
      canValidate && (alg match {
        case _: algorithms.JwtAsymmetricAlgorithm =>
          algorithm match {
            case _: algorithms.JwtRSAAlgorithm   => alg.isInstanceOf[algorithms.JwtRSAAlgorithm] && canValidate
            case _: algorithms.JwtECDSAAlgorithm => alg.isInstanceOf[algorithms.JwtECDSAAlgorithm] && canValidate
            case _: algorithms.JwtEdDSAAlgorithm => alg.isInstanceOf[algorithms.JwtEdDSAAlgorithm] && canValidate
            case _                               => false
          }
        case _ => false
      })
    }

    override def canValidate: Boolean = keyPair.getPublic != null
    override def validate(token: String): Try[JsObject] = if (canValidate) {
      JwtSprayJson.decodeJson(token, keyPair.getPublic)
    } else {
      // This key should have already been excluded as a candidate for validation
      Failure(new RuntimeException("Key does not have a public component"))
    }

    override def algorithmName: String = algorithm.name
  }

  case class JwtSymmetricAlgorithmSecret(algorithm: algorithms.JwtHmacAlgorithm, key: SecretKey)
    extends JwtAlgorithmSecret {
    override def canValidateAlgorithm(alg: JwtAlgorithm): Boolean = alg.isInstanceOf[algorithms.JwtHmacAlgorithm]
    override def canValidate: Boolean = true
    override def validate(token: String): Try[JsObject] = JwtSprayJson.decodeJson(token, key)
    override def algorithmName: String = algorithm.name
  }

  case object JwtNoneAlgorithmSecret extends JwtAlgorithmSecret {
    private val noSignatureOptions = JwtOptions.DEFAULT.copy(signature = false)
    override def canValidateAlgorithm(alg: JwtAlgorithm): Boolean = false
    override def canValidate: Boolean = true
    override def validate(token: String): Try[JsObject] = JwtSprayJson.decodeJson(token, noSignatureOptions)
    override def algorithmName: String = "none"
  }

  case class JwtSecret(keyId: String, issuer: Option[String], secret: JwtAlgorithmSecret) {
    val header: JsObject = new JsObject(Vector("alg" -> JsString(secret.algorithmName), "kid" -> JsString(keyId)).toMap)
  }

  private def secretLiteralNotSupportedWithAsymmetricAlgorithm(keyId: String, algorithm: String) = JwtRejection(
    s"Secret literal for key id [$keyId] not supported with asymmetric algorithms: $algorithm." +
      "Secret literals are only supported with symmetric (HMAC) algorithms.")

}