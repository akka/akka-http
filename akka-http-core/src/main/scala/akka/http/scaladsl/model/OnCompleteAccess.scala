/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

class OnCompleteAccess(var onComplete: Option[HttpResponse => HttpResponse] = None) {
  def add(newOnComplete: HttpResponse => HttpResponse): Unit =
    onComplete = if (onComplete.isEmpty) Some(newOnComplete) else onComplete.map { func => func andThen newOnComplete }
}
