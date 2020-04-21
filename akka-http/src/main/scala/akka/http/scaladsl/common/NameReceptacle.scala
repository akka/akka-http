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
  /**
   * Extract the value as the specified type.
   * You need a matching [[akka.http.scaladsl.unmarshalling.Unmarshaller]] in scope for that to work.
   */
  def as[B] = new NameReceptacle[B](name)

  /**
   * Extract the value as the specified type with the given [[akka.http.scaladsl.unmarshalling.Unmarshaller]].
   */
  def as[B](unmarshaller: Unmarshaller[T, B])(implicit fsu: FSU[T]) =
    new NameUnmarshallerReceptacle(name, fsu.transform[B](implicit ec => implicit mat => { _ flatMap unmarshaller.apply }))

  /**
   * Extract the optional value as `Option[String]`.
   * Symbolic alias for [[optional]].
   */
  def ? = new NameOptionReceptacle[T](name)

  /**
   * Extract the optional value as `Option[String]`.
   */
  def optional = new NameOptionReceptacle[T](name)

  /**
   * Extract the optional value as `String`, if it is missing use the given default value.
   * Symbolic alias for [[withDefault]].
   */
  def ?[B](default: B) = new NameDefaultReceptacle(name, default)

  /**
   * Extract the optional value as `String`, if it is missing use the given default value.
   */
  def withDefault[B](default: B) = new NameDefaultReceptacle(name, default)

  /**
   * Require the given value and extract nothing.
   * Reject if it is missing or has a different value.
   * Symbolic alias for [[requiredValue]].
   */
  def ![B](requiredValue: B) = new RequiredValueReceptacle(name, requiredValue)

  /**
   * Require the given value and extract nothing.
   * Reject if it is missing or has a different value.
   */
  def requiredValue[B](requiredValue: B) = new RequiredValueReceptacle(name, requiredValue)

  /**
   * Extract multiple occurrences as `Iterable[String]`.
   * Symbolic alias for [[repeated]].
   */
  def * = new RepeatedValueReceptacle[T](name)

  /**
   * Extract multiple occurrences as `Iterable[String]`.
   */
  def repeated = new RepeatedValueReceptacle[T](name)
}

class NameUnmarshallerReceptacle[T](val name: String, val um: FSU[T]) {
  /**
   * Extract the value as the specified type.
   * You need a matching [[akka.http.scaladsl.unmarshalling.Unmarshaller]] in scope for that to work.
   */
  def as[B](implicit unmarshaller: Unmarshaller[T, B]) =
    new NameUnmarshallerReceptacle(name, um.transform[B](implicit ec => implicit mat => { _ flatMap unmarshaller.apply }))

  /**
   * Extract the optional value as `Option[T]`.
   * Symbolic alias for [[optional]].
   */
  def ? = new NameOptionUnmarshallerReceptacle[T](name, um)

  /**
   * Extract the optional value as `Option[T]`.
   * Symbolic alias for [[optional]].
   */
  def optional = new NameOptionUnmarshallerReceptacle[T](name, um)

  /**
   * Extract the optional value as `T`, if it is missing use the given default value.
   * Symbolic alias for [[withDefault]].
   */
  def ?(default: T) = new NameDefaultUnmarshallerReceptacle(name, default, um)

  /**
   * Extract the optional value as `T`, if it is missing use the given default value.
   */
  def withDefault(default: T) = new NameDefaultUnmarshallerReceptacle(name, default, um)

  /**
   * Require the given value and extract nothing.
   * Reject if it is missing or has a different value.
   * Symbolic alias for [[requiredValue]].
   */
  def !(requiredValue: T) = new RequiredValueUnmarshallerReceptacle(name, requiredValue, um)

  /**
   * Require the given value and extract nothing.
   * Reject if it is missing or has a different value.
   */
  def requiredValue(requiredValue: T) = new RequiredValueUnmarshallerReceptacle(name, requiredValue, um)

  /**
   * Extract multiple occurrences as `Iterable[String]`.
   * Symbolic alias for [[repeated]].
   */
  def * = new RepeatedValueUnmarshallerReceptacle[T](name, um)

  /**
   * Extract multiple occurrences as `Iterable[String]`.
   */
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
