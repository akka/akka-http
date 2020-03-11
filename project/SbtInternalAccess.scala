/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package sbt{
  package object access {
    type Aggregation = sbt.internal.Aggregation
    val Aggregation = sbt.internal.Aggregation

    private val showRunMethod = {
      val method = Class.forName("sbt.internal.Aggregation$").getDeclaredMethods.find(_.getName == "showRun").get
      method.setAccessible(true)
      method
    }
    def AggregationShowRun[T](complete: sbt.internal.Aggregation.Complete[T], show: sbt.internal.Aggregation.ShowConfig)(
      implicit display: Show[ScopedKey[_]]
    ): Unit = showRunMethod.invoke(Aggregation, complete, show, display)
  }
}