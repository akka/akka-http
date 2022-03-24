/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
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

  def twoParameters: Directive[(String, Int)] =
    formFields(("name", "age".as[Int]))
  def twoParametersRoute: Route =
    twoParameters { (name, age) =>
      complete(s"$name $age")
    }
}
