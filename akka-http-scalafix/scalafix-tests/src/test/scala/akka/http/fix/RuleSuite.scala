/*
 * Copyright (C) 2020-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.fix

import org.scalatest.funspec.AnyFunSpecLike
import scalafix.testkit.AbstractSemanticRuleSuite

class RuleSuite extends AbstractSemanticRuleSuite with AnyFunSpecLike {
  runAllTests()
}
