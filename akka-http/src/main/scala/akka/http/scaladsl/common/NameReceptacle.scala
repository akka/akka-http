/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.common

import akka.http.scaladsl.unmarshalling.{ Unmarshaller, FromStringUnmarshaller => FSU }

private[http] trait ToNameReceptacleEnhancements {
  implicit def _symbol2NR(symbol: Symbol): NameReceptacle[String] = new NameReceptacle[String](symbol.name)
  @annotation.implicitAmbiguous("Akka HTTP's `*` decorator conflicts with Scala's String.`*` method. Use `.repeated` instead.")
  implicit def _string2NR(string: String): NameReceptacle[String] = new NameReceptacle[String](string)
}
object ToNameReceptacleEnhancements extends ToNameReceptacleEnhancements

class NameReceptacle[T](val name: String) {
  def as[B] = new NameReceptacle[B](name)
  def as[B](unmarshaller: Unmarshaller[T, B])(implicit fsu: FSU[T]) =
    new NameUnmarshallerReceptacle(name, fsu.transform[B](implicit ec => implicit mat => { _ flatMap unmarshaller.apply }))
  /** Symbolic alias for [[optional]]. */
  def ? = new NameOptionReceptacle[T](name)
  def optional = new NameOptionReceptacle[T](name)
  /** Symbolic alias for [[withDefault]]. */
  def ?[B](default: B) = new NameDefaultReceptacle(name, default)
  def withDefault[B](default: B) = new NameDefaultReceptacle(name, default)
  /** Symbolic alias for [[requiredValue]]. */
  def ![B](requiredValue: B) = new RequiredValueReceptacle(name, requiredValue)
  def requiredValue[B](requiredValue: B) = new RequiredValueReceptacle(name, requiredValue)
  /** Symbolic alias for [[repeated]]. */
  def * = new RepeatedValueReceptacle[T](name)
  def repeated = new RepeatedValueReceptacle[T](name)
}

class NameUnmarshallerReceptacle[T](val name: String, val um: FSU[T]) {
  def as[B](implicit unmarshaller: Unmarshaller[T, B]) =
    new NameUnmarshallerReceptacle(name, um.transform[B](implicit ec => implicit mat => { _ flatMap unmarshaller.apply }))
  /** Symbolic alias for [[optional]]. */
  def ? = new NameOptionUnmarshallerReceptacle[T](name, um)
  def optional = new NameOptionUnmarshallerReceptacle[T](name, um)
  /** Symbolic alias for [[withDefault]]. */
  def ?(default: T) = new NameDefaultUnmarshallerReceptacle(name, default, um)
  def withDefault(default: T) = new NameDefaultUnmarshallerReceptacle(name, default, um)
  /** Symbolic alias for [[requiredValue]]. */
  def !(requiredValue: T) = new RequiredValueUnmarshallerReceptacle(name, requiredValue, um)
  def requiredValue(requiredValue: T) = new RequiredValueUnmarshallerReceptacle(name, requiredValue, um)
  /** Symbolic alias for [[repeated]]. */
  def * = new RepeatedValueUnmarshallerReceptacle[T](name, um)
  def repeated = new RepeatedValueUnmarshallerReceptacle[T](name, um)
}

class NameOptionReceptacle[T](val name: String)

class NameDefaultReceptacle[T](val name: String, val default: T)

class RequiredValueReceptacle[T](val name: String, val requiredValue: T)

class RepeatedValueReceptacle[T](val name: String)

class NameOptionUnmarshallerReceptacle[T](val name: String, val um: FSU[T])

class NameDefaultUnmarshallerReceptacle[T](val name: String, val default: T, val um: FSU[T])

class RequiredValueUnmarshallerReceptacle[T](val name: String, val requiredValue: T, val um: FSU[T])

class RepeatedValueUnmarshallerReceptacle[T](val name: String, val um: FSU[T])
