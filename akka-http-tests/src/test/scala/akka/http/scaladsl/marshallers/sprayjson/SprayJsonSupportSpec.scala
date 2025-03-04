/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import java.lang.StringBuilder

import akka.http.scaladsl.marshallers.{ JsonSupportSpec, Employee }
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import spray.json.{ JsValue, PrettyPrinter, JsonPrinter, DefaultJsonProtocol }

import scala.collection.immutable.ListMap
import spray.json.RootJsonFormat

class SprayJsonSupportSpec extends JsonSupportSpec {
  object EmployeeJsonProtocol extends DefaultJsonProtocol {
    implicit val employeeFormat: RootJsonFormat[Employee] = jsonFormat5(Employee.apply)
  }
  import EmployeeJsonProtocol._

  implicit val orderedFieldPrint: JsonPrinter = new PrettyPrinter {
    override protected def printObject(members: Map[String, JsValue], sb: StringBuilder, indent: Int): Unit =
      super.printObject(ListMap(members.toSeq.sortBy(_._1): _*), sb, indent)
  }

  implicit def marshaller: ToEntityMarshaller[Employee] = SprayJsonSupport.sprayJsonMarshaller[Employee]
  implicit def unmarshaller: FromEntityUnmarshaller[Employee] = SprayJsonSupport.sprayJsonUnmarshaller[Employee]
}
