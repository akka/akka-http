/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.unmarshalling

import java.util.concurrent.CompletionStage

import akka.annotation.InternalApi

object StringUnmarshaller {
  /**
   * Turns the given asynchronous function into an unmarshaller from String to B.
   */
  def async[B](f: java.util.function.Function[String, CompletionStage[B]]): Unmarshaller[String, B] = Unmarshaller.async(f)

  /**
   * Turns the given function into an unmarshaller from String to B.
   */
  def sync[B](f: java.util.function.Function[String, B]): Unmarshaller[String, B] = Unmarshaller.sync(f)
}

/**
 * INTERNAL API
 */
@InternalApi
private[unmarshalling] object StringUnmarshallerPredef extends akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers {

}

