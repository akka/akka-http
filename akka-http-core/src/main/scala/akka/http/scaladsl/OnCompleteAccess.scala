package akka.http.scaladsl

import akka.http.scaladsl.model.HttpResponse

case class OnCompleteAccess(var onComplete: HttpResponse => HttpResponse = res => res) {
  def add(newOnComplete: HttpResponse => HttpResponse): Unit = onComplete = onComplete andThen newOnComplete
}
