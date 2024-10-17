/*
 * Copyright (C) 2019-2024 Lightbend Inc. <https://www.lightbend.com>
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
