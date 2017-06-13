/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl

import akka.http.javadsl.testkit.JUnitRouteTest
import org.scalatest.junit.JUnitSuiteLike

/** Same functionality as JUnitRouteTest but will allows to run the tests using scalatest */
abstract class ScalaTestJunitRouteTest extends JUnitRouteTest with JUnitSuiteLike
