/*
 * Copyright (C) 2019-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.scaladsl.server
package directives

import Directives._

object CompatFormField {
  def oneParameter: Directive1[Int] =
    formField("num".as[Int])
  def oneParameterRoute: Route =
    oneParameter { num =>
      complete(num.toString)
    }
}
