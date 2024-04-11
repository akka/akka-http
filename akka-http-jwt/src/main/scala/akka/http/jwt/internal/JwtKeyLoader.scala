/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.jwt.internal

import JwtSupport.{ JwtAsymmetricAlgorithmSecret, PrivateKeyConfig, PublicKeyConfig }
import akka.annotation.InternalApi
import akka.pki.pem.{ DERPrivateKeyLoader, PEMDecoder, PEMLoadingException }
import com.typesafe.config.Config
import pdi.jwt.{ JwtAlgorithm, algorithms }

import java.io.File
import java.nio.file.Files
import java.security.spec.{ PKCS8EncodedKeySpec, X509EncodedKeySpec }
import java.security.{ KeyFactory, KeyPair }
import javax.crypto.spec.SecretKeySpec

/**
 * INTERNAL API
 *
 * Loads a key from a directory.
 */
@InternalApi
private[jwt] object JwtKeyLoader {

  def loadKey(keyId: String, algorithm: JwtAlgorithm, secretConfig: Config): JwtSupport.JwtAlgorithmSecret = {
    algorithm match {

      case symmetric: algorithms.JwtHmacAlgorithm =>
        val secretKeyFile = new File(secretConfig.getString("secret-path"))
        if (secretKeyFile.exists()) {
          JwtSupport.JwtSymmetricAlgorithmSecret(
            symmetric,
            new SecretKeySpec(Files.readAllBytes(secretKeyFile.toPath), algorithm.fullName))
        } else {
          throwIAE(s"Expected a symmetric secret configured for JWT key with id [$keyId]")
        }

      case asymmetric: algorithms.JwtAsymmetricAlgorithm =>
        val keyAlgo = asymmetric match {
          case _: algorithms.JwtRSAAlgorithm   => "RSA"
          case _: algorithms.JwtECDSAAlgorithm => "EC"
          case _: algorithms.JwtEdDSAAlgorithm => "EdDSA"
        }

        val publicKeyFile = new File(secretConfig.getString(PublicKeyConfig))
        if (publicKeyFile.exists()) {
          val pem = loadPem(publicKeyFile, keyId)
          pem.label match {
            case "PUBLIC KEY" =>
              try {
                val publicKey = KeyFactory.getInstance(keyAlgo).generatePublic(new X509EncodedKeySpec(pem.bytes))
                JwtAsymmetricAlgorithmSecret(asymmetric, publicKey)
              } catch {
                case e: Exception => throwIAE(s"Error decoding JWT public key from key id [$keyId]: ${e.getMessage}", e)
              }
            case _ => throwIAE(s"Unsupported JWT public key format for key id [$keyId]: ${pem.label}")
          }
        } else {
          throwIAE(s"Public key configured for JWT key with id [$keyId] could not be found or read.")
        }

      case other =>
        throwIAE(s"Unknown JWT algorithm for key id [$keyId]: $other")
    }

  }

  private def loadPem(file: File, keyId: String): PEMDecoder.DERData = {
    try {
      PEMDecoder.decode(new String(Files.readAllBytes(file.toPath)))
    } catch {
      case e: PEMLoadingException =>
        throwIAE(s"Error PEM decoding JWT public key from key id [$keyId]: ${e.getMessage}", e)
    }
  }

  private def throwIAE(msg: String, e: Exception = null): Nothing = throw new IllegalArgumentException(msg, e)

}
