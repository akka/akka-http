/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import java.util
import akka.http.javadsl.{ model => jm }
import akka.http.impl.util._
import akka.http.impl.util.JavaMapping.Implicits._

/**
 * Note: the token of challenge is stored in the params Map as a parameter whose name is empty String("") for binary
 * compatibility, but it will be parsed and rendered correctly.
 */
final case class HttpChallenge(scheme: String, realm: String,
                               params: Map[String, String] = Map.empty) extends jm.headers.HttpChallenge with ValueRenderable {

  def render[R <: Rendering](r: R): r.type = {
    r ~~ scheme

    val paramsNoToken = params.filterKeys(_ != "")

    if (params.contains("")) r ~~ " " ~~ params("")
    if (realm != null) r ~~ " realm=" ~~#! realm
    if (paramsNoToken.nonEmpty) {
      if (realm == null) r ~~ ' ' else r ~~ ','
      r ~~ paramsNoToken.head._1 ~~ '=' ~~# paramsNoToken.head._2
      paramsNoToken.tail.foreach { case (k, v) => r ~~ ',' ~~ k ~~ '=' ~~# v }
    }

    r
  }

  /** Java API */
  def getParams: util.Map[String, String] = params.asJava
}

// FIXME: AbstractFunction3 required for bin compat. remove in Akka 10.0 and change realm in case class to option #20786
object HttpChallenge extends scala.runtime.AbstractFunction3[String, String, Map[String, String], HttpChallenge] {

  def apply(scheme: String, realm: Option[String]): HttpChallenge =
    HttpChallenge(scheme, realm.orNull, Map.empty[String, String])

  def apply(scheme: String, realm: Option[String], params: Map[String, String]): HttpChallenge =
    HttpChallenge(scheme, realm.orNull, params)

}

object HttpChallenges {

  def basic(realm: String): HttpChallenge = HttpChallenge("Basic", realm, Map("charset" -> "UTF-8"))

  def oAuth2(realm: String): HttpChallenge = HttpChallenge("Bearer", realm)
}
