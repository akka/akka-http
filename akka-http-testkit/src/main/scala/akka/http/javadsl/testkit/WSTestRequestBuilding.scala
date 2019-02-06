/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.testkit

import akka.http.javadsl.model.ws.Message
import akka.http.javadsl.model.{ HttpRequest, Uri }
import akka.http.scaladsl.{ model ⇒ sm }
import akka.stream.javadsl.Flow

import akka.http.scaladsl.{ testkit ⇒ st }

import akka.http.impl.util.JavaMapping.Implicits._
import scala.collection.JavaConverters._
import akka.stream.{ Materializer, scaladsl }

trait WSTestRequestBuilding {

  def WS[T](uri: Uri, clientSideHandler: Flow[Message, Message, T], materializer: Materializer): HttpRequest = {
    WS(uri, clientSideHandler, materializer, java.util.Collections.emptyList())
  }

  def WS[T](
    uri:               Uri,
    clientSideHandler: Flow[Message, Message, T],
    materializer:      Materializer,
    subprotocols:      java.util.List[String]): HttpRequest = {

    val handler = scaladsl.Flow[sm.ws.Message].map(_.asJava).via(clientSideHandler).map(_.asScala)
    st.WSTestRequestBuilding.WS(uri.asScala, handler, subprotocols.asScala.toSeq)(materializer)
  }

}
