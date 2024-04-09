/*
 * Copyright 2022 Lightbend Inc.
 */

package akka.http.jwt.util

import akka.http.jwt.util.JwtSupport.{ JwtAsymmetricAlgorithmSecret, JwtNoneAlgorithmSecret }
import pdi.jwt.{ JwtAlgorithm, algorithms }

import java.io.File
import java.nio.file.Files
import java.security.spec.{ PKCS8EncodedKeySpec, X509EncodedKeySpec }
import java.security.{ KeyFactory, KeyPair }
import javax.crypto.spec.SecretKeySpec
import akka.pki.pem.{ DERPrivateKeyLoader, PEMDecoder, PEMLoadingException }

object JwtKeyLoader {

  def loadKey(keyId: String, algorithm: JwtAlgorithm, directory: File): JwtSupport.JwtAlgorithmSecret = {
    algorithm match {
      case symmetric: algorithms.JwtHmacAlgorithm =>
        val secretKeyFile = new File(directory, "secret.key")
        if (secretKeyFile.exists()) {
          JwtSupport.JwtSymmetricAlgorithmSecret(
            symmetric,
            new SecretKeySpec(Files.readAllBytes(secretKeyFile.toPath), algorithm.fullName))
        } else {
          throw new RuntimeException("Jwt.expectedHmacSecret(keyId)")
        }
      case asymmetric: algorithms.JwtAsymmetricAlgorithm =>
        val keyAlgo = asymmetric match {
          case _: algorithms.JwtRSAAlgorithm   => "RSA"
          case _: algorithms.JwtECDSAAlgorithm => "EC"
          case _: algorithms.JwtEdDSAAlgorithm => "EdDSA"
        }
        val publicKeyFile = new File(directory, "public.key")
        val privateKeyFile = new File(directory, "private.key")

        val publicKey = if (publicKeyFile.exists()) {
          val pem = loadPem(publicKeyFile, keyId, "public")
          pem.label match {
            case "PUBLIC KEY" =>
              try {
                Some(KeyFactory.getInstance(keyAlgo).generatePublic(new X509EncodedKeySpec(pem.bytes)))
              } catch {
                case e: Exception => throw new RuntimeException("Jwt.keyDecodingError(keyId, public, e.getMessage)")
              }
            case _ => throw new RuntimeException("Jwt.unknownPublicKeyType(keyId, pem.label)")
          }
        } else None

        val privateKey = if (privateKeyFile.exists()) {
          val pem = loadPem(privateKeyFile, keyId, "private")
          pem.label match {
            case "PRIVATE KEY" =>
              // PKCS8
              // One thing we could do is validate that the key type in the pkcs8 spec matches the algorithm to give
              // a better error message.
              try {
                Some(KeyFactory.getInstance(keyAlgo).generatePrivate(new PKCS8EncodedKeySpec(pem.bytes)))
              } catch {
                case e: Exception => throw new RuntimeException("Jwt.keyDecodingError(keyId, private, e.getMessage)")
              }

            case "RSA PRIVATE KEY" =>
              try {
                Some(DERPrivateKeyLoader.load(pem))
              } catch {
                case e: Exception => throw new RuntimeException("Jwt.keyDecodingError(keyId, private, e.getMessage)")
              }

            case "EC PRIVATE KEY" =>
              throw new RuntimeException("Jwt.ecPrivateKeyNotSupported(keyId)")

            case _ =>
              throw new RuntimeException("Jwt.unknownPrivateKeyType(keyId, pem.label)")
          }
        } else None

        (privateKey, publicKey) match {
          case (None, None) =>
            throw new RuntimeException("Jwt.expectedAsymmetricSecret(keyId)")
          case (maybePriv, maybePub) =>
            JwtAsymmetricAlgorithmSecret(asymmetric, new KeyPair(maybePub.orNull, maybePriv.orNull))
        }
      case other =>
        throw new RuntimeException("Jwt.unknownAlgorithm")
    }

  }

  private def loadPem(file: File, keyId: String, name: String) = {
    try {
      PEMDecoder.decode(new String(Files.readAllBytes(file.toPath)))
    } catch {
      case e: PEMLoadingException =>
        throw new RuntimeException("Jwt.pemDecodingError(keyId, name, e.getMessage)")
    }
  }

}
