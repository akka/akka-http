/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.annotation.InternalApi
import akka.http.impl.util._
import akka.http.scaladsl.common._
import akka.http.scaladsl.server.directives.RouteDirectives._
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.scaladsl.util.FastFuture._

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import BasicDirectives._
import akka.http.ccompat.pre213
import akka.http.ccompat.since213

/**
 * @groupname form Form field directives
 * @groupprio form 90
 */
trait FormFieldDirectives extends FormFieldDirectivesInstances with ToNameReceptacleEnhancements {
  import FormFieldDirectives._

  /**
   * Extracts HTTP form fields from the request as a ``Map[String, String]``.
   *
   * @group form
   */
  def formFieldMap: Directive1[Map[String, String]] = _formFieldMap

  /**
   * Extracts HTTP form fields from the request as a ``Map[String, List[String]]``.
   *
   * @group form
   */
  def formFieldMultiMap: Directive1[Map[String, List[String]]] = _formFieldMultiMap

  /**
   * Extracts HTTP form fields from the request as a ``Seq[(String, String)]``.
   *
   * @group form
   */
  def formFieldSeq: Directive1[immutable.Seq[(String, String)]] = _formFieldSeq

  /**
   * Extracts an HTTP form field from the request.
   * Rejects the request if the defined form field matcher(s) don't match.
   *
   * @group form
   */
  @pre213
  @deprecated("Use new `formField` overloads with FieldSpec parameters. Kept for binary compatibility", "10.2.0")
  private[http] def formField(pdm: FieldMagnet): pdm.Out = formFields(pdm)

  /**
   * Extracts an HTTP form field from the request.
   * Rejects the request if the defined form field matcher(s) don't match.
   *
   * @group form
   */
  @since213
  @deprecated("Use new `formField` overloads with FieldSpec parameters. Kept for binary compatibility", "10.2.0")
  private[http] def formField(pdm: FieldMagnet): Directive[pdm.U] = formFields(pdm)

  /**
   * Extracts a number of HTTP form field from the request.
   * Rejects the request if the defined form field matcher(s) don't match.
   *
   * @group form
   */
  @pre213
  @deprecated("Use new `formField` overloads with FieldSpec parameters. Kept for binary compatibility", "10.2.0")
  private[http] def formFields(pdm: FieldMagnet): pdm.Out =
    pdm.convert(toStrictEntity(StrictForm.toStrictTimeout).wrap { pdm() })

  /**
   * Extracts a number of HTTP form field from the request.
   * Rejects the request if the defined form field matcher(s) don't match.
   *
   * @group form
   */
  @since213
  @deprecated("Use new `formField` overloads with FieldSpec parameters. Kept for binary compatibility", "10.2.0")
  private[http] def formFields(pdm: FieldMagnet): Directive[pdm.U] =
    toStrictEntity(StrictForm.toStrictTimeout).wrap { pdm() }
}

object FormFieldDirectives extends FormFieldDirectives {

  private val _formFieldSeq: Directive1[immutable.Seq[(String, String)]] = {
    import FutureDirectives._
    import akka.http.scaladsl.unmarshalling._

    extract { ctx =>
      import ctx.{ executionContext, materializer }
      Unmarshal(ctx.request.entity).to[StrictForm].fast.flatMap { form =>
        val fields = form.fields.collect {
          case (name, field) if name.nonEmpty =>
            Unmarshal(field).to[String].map(fieldString => (name, fieldString))
        }
        Future.sequence(fields)
      }
    }.flatMap { sequenceF =>
      onComplete(sequenceF).flatMap {
        case Success(x)                                  => provide(x)
        case Failure(x: UnsupportedContentTypeException) => reject(UnsupportedRequestContentTypeRejection(x.supported, x.actualContentType))
        case Failure(x)                                  => reject(MalformedRequestContentRejection(x.getMessage.nullAsEmpty, x))
      }
    }
  }

  private val _formFieldMultiMap: Directive1[Map[String, List[String]]] = {
    @tailrec def append(
      map:    Map[String, List[String]],
      fields: immutable.Seq[(String, String)]): Map[String, List[String]] = {
      if (fields.isEmpty) {
        map
      } else {
        val (key, value) = fields.head
        append(map.updated(key, value :: map.getOrElse(key, Nil)), fields.tail)
      }
    }

    _formFieldSeq.map {
      case seq =>
        append(immutable.TreeMap.empty, seq)
    }
  }

  private val _formFieldMap: Directive1[Map[String, String]] = _formFieldSeq.map(toMap)

  private def toMap(seq: Seq[(String, String)]): Map[String, String] = immutable.TreeMap(seq: _*)

  trait FieldSpec {
    type Out
    def get: Directive1[Out]
  }
  object FieldSpec {
    type Aux[T] = FieldSpec { type Out = T }

    def apply[T](directive: Directive1[T]): FieldSpec.Aux[T] = new FieldSpec {
      type Out = T
      def get: Directive1[T] = toStrictEntity(StrictForm.toStrictTimeout).wrap { directive }
    }

    import Impl._
    import akka.http.scaladsl.unmarshalling.{ FromStrictFormFieldUnmarshaller => FSFFU, _ }
    type FSFFOU[T] = Unmarshaller[Option[StrictForm.Field], T]

    implicit def forString(fieldName: String): FieldSpec.Aux[String] = forName(fieldName, stringFromStrictForm)
    implicit def forSymbol(symbol: Symbol): FieldSpec.Aux[String] = forName(symbol.name, stringFromStrictForm)
    implicit def forNR[T](r: NameReceptacle[T])(implicit fu: FSFFU[T]): FieldSpec.Aux[T] = forName(r.name, fu)
    implicit def forNUR[T](r: NameUnmarshallerReceptacle[T]): FieldSpec.Aux[T] = forName(r.name, StrictForm.Field.unmarshallerFromFSU(r.um))
    implicit def forNOR[T](r: NameOptionReceptacle[T])(implicit fu: FSFFOU[T]): FieldSpec.Aux[Option[T]] = forName(r.name, fu)
    implicit def forNDR[T](r: NameDefaultReceptacle[T])(implicit fu: FSFFOU[T]): FieldSpec.Aux[T] = forName(r.name, fu withDefaultValue r.default)
    implicit def forNOUR[T](r: NameOptionUnmarshallerReceptacle[T]): FieldSpec.Aux[Option[T]] = forName(r.name, StrictForm.Field.unmarshallerFromFSU(r.um): FSFFOU[T])
    implicit def forNDUR[T](r: NameDefaultUnmarshallerReceptacle[T]): FieldSpec.Aux[T] = forName(r.name, (StrictForm.Field.unmarshallerFromFSU(r.um): FSFFOU[T]) withDefaultValue r.default)

    //////////////////// required formField support ////////////////////

    implicit def forRVR[T](r: RequiredValueReceptacle[T])(implicit fu: FSFFU[T]): FieldSpec.Aux[Unit] =
      forNameRequired(r.name, fu, r.requiredValue)
    implicit def forRVDR[T](r: RequiredValueUnmarshallerReceptacle[T]): FieldSpec.Aux[Unit] =
      forNameRequired(r.name, StrictForm.Field.unmarshallerFromFSU(r.um), r.requiredValue)

    //////////////////// repeated formField support ////////////////////

    implicit def forRepVR[T](r: RepeatedValueReceptacle[T])(implicit fu: FSFFU[T]): FieldSpec.Aux[Iterable[T]] =
      forNameRepeated(r.name, fu)
    implicit def forRepVDR[T](r: RepeatedValueUnmarshallerReceptacle[T]): FieldSpec.Aux[Iterable[T]] =
      forNameRepeated(r.name, StrictForm.Field.unmarshallerFromFSU(r.um))

    private def forName[T](name: String, fu: FSFFOU[T]): FieldSpec.Aux[T] = FieldSpec(filter(name, fu))
    private def forNameRequired[T](name: String, fsu: FSFFOU[T], requiredValue: T): FieldSpec.Aux[Unit] = FieldSpec(requiredFilter(name, fsu, requiredValue).tmap(_ => Tuple1(())))
    private def forNameRepeated[T](name: String, fsu: FSFFU[T]): FieldSpec.Aux[Iterable[T]] = FieldSpec(repeatedFilter(name, fsu))
  }

  @deprecated("Use new `formField` overloads with FieldSpec parameters. Kept for binary compatibility", since = "10.2.0")
  sealed trait FieldMagnet {
    type U
    def apply(): Directive[U]

    // Compatibility helper:
    // type R = Directive[U]
    // but we don't put it in here, so the compiler will produce AnyRef instead of `Directive` as the result type
    // of the `formFields` directives above for compatibility reasons. We also need to provide a type-safe conversion function.
    type Out
    def convert(d: Directive[U]): Out
  }

  @deprecated("Use new `formField` overloads with FieldSpec parameters. Kept for binary compatibility", since = "10.2.0")
  object FieldMagnet {
    implicit def apply[T](value: T)(implicit fdef: FieldDef[T]): FieldMagnet { type U = fdef.U; type Out = Directive[fdef.U] } =
      new FieldMagnet {
        type U = fdef.U
        def apply(): Directive[U] = fdef(value)

        type Out = Directive[fdef.U]
        def convert(d: Directive[fdef.U]): Directive[fdef.U] = d
      }
  }

  @deprecated("Use new `formFields` overloads with FieldSpec parameters. Kept for binary compatibility", since = "10.2.0")
  type FieldDefAux[A, B] = FieldDef[A] { type U = B }

  @deprecated("Use new `formFields` overloads with FieldSpec parameters. Kept for binary compatibility", since = "10.2.0")
  sealed trait FieldDef[T] {
    type U
    def apply(value: T): Directive[U]
  }
  @deprecated("Use new `formFields` overloads with FieldSpec parameters. Kept for binary compatibility", since = "10.2.0")
  object FieldDef {
    protected def fieldDef[A, B](f: A => Directive[B]): FieldDefAux[A, B] =
      new FieldDef[A] {
        type U = B
        def apply(value: A): Directive[B] = f(value)
      }

    import Impl._
    import akka.http.scaladsl.unmarshalling.{ FromStrictFormFieldUnmarshaller => FSFFU, _ }

    type SFU = FromEntityUnmarshaller[StrictForm]
    type FSFFOU[T] = Unmarshaller[Option[StrictForm.Field], T]

    protected def extractField[A, B](f: A => Directive1[B]): FieldDefAux[A, Tuple1[B]] = fieldDef(f)

    def forString: FieldDefAux[String, Tuple1[String]] =
      extractField[String, String] { fieldName => filter(fieldName, stringFromStrictForm) }
    def forSymbol: FieldDefAux[Symbol, Tuple1[String]] =
      extractField[Symbol, String] { symbol => filter(symbol.name, stringFromStrictForm) }
    def forNR[T](implicit fu: FSFFU[T]): FieldDefAux[NameReceptacle[T], Tuple1[T]] =
      extractField[NameReceptacle[T], T] { nr => filter(nr.name, fu) }
    def forNUR[T]: FieldDefAux[NameUnmarshallerReceptacle[T], Tuple1[T]] =
      extractField[NameUnmarshallerReceptacle[T], T] { nr => filter(nr.name, StrictForm.Field.unmarshallerFromFSU(nr.um)) }
    def forNOR[T](implicit fu: FSFFOU[T]): FieldDefAux[NameOptionReceptacle[T], Tuple1[Option[T]]] =
      extractField[NameOptionReceptacle[T], Option[T]] { nr => filter[Option[T]](nr.name, fu) }
    def forNDR[T](implicit fu: FSFFOU[T]): FieldDefAux[NameDefaultReceptacle[T], Tuple1[T]] =
      extractField[NameDefaultReceptacle[T], T] { nr => filter(nr.name, fu withDefaultValue nr.default) }
    def forNOUR[T]: FieldDefAux[NameOptionUnmarshallerReceptacle[T], Tuple1[Option[T]]] =
      extractField[NameOptionUnmarshallerReceptacle[T], Option[T]] { nr => filter[Option[T]](nr.name, StrictForm.Field.unmarshallerFromFSU(nr.um): FSFFOU[T]) }
    def forNDUR[T]: FieldDefAux[NameDefaultUnmarshallerReceptacle[T], Tuple1[T]] =
      extractField[NameDefaultUnmarshallerReceptacle[T], T] { nr => filter(nr.name, (StrictForm.Field.unmarshallerFromFSU(nr.um): FSFFOU[T]) withDefaultValue nr.default) }

    //////////////////// required formField support ////////////////////

    def forRVR[T](implicit fu: FSFFU[T]): FieldDefAux[RequiredValueReceptacle[T], Unit] =
      fieldDef[RequiredValueReceptacle[T], Unit] { rvr => requiredFilter(rvr.name, fu, rvr.requiredValue) }
    def forRVDR[T]: FieldDefAux[RequiredValueUnmarshallerReceptacle[T], Unit] =
      fieldDef[RequiredValueUnmarshallerReceptacle[T], Unit] { rvr => requiredFilter(rvr.name, StrictForm.Field.unmarshallerFromFSU(rvr.um), rvr.requiredValue) }

    //////////////////// repeated formField support ////////////////////

    def forRepVR[T](implicit fu: FSFFU[T]): FieldDefAux[RepeatedValueReceptacle[T], Tuple1[Iterable[T]]] =
      extractField[RepeatedValueReceptacle[T], Iterable[T]] { rvr => repeatedFilter(rvr.name, fu) }
    def forRepVDR[T]: FieldDefAux[RepeatedValueUnmarshallerReceptacle[T], Tuple1[Iterable[T]]] =
      extractField[RepeatedValueUnmarshallerReceptacle[T], Iterable[T]] { rvr => repeatedFilter(rvr.name, StrictForm.Field.unmarshallerFromFSU(rvr.um)) }

    //////////////////// tuple support ////////////////////

    import akka.http.scaladsl.server.util.BinaryPolyFunc
    import akka.http.scaladsl.server.util.TupleOps._

    def forTuple[T, O](implicit fold: FoldLeft[Directive0, T, ConvertFieldDefAndConcatenate.type] { type Out = Directive[O] }): FieldDefAux[T, O] =
      fieldDef(fold(pass, _))

    object ConvertFieldDefAndConcatenate extends BinaryPolyFunc {
      implicit def from[P, TA, TB](implicit fdef: FieldDefAux[P, TB], ev: Join[TA, TB]): BinaryPolyFunc.Case[Directive[TA], P, ConvertFieldDefAndConcatenate.type] { type Out = Directive[ev.Out] } =
        at[Directive[TA], P].apply[Directive[ev.Out]] { (a, t) => a & fdef(t) }
    }
  }

  @InternalApi
  private[http] object Impl {
    import BasicDirectives._
    import FutureDirectives._
    import RouteDirectives._
    import akka.http.scaladsl.unmarshalling.{ FromStrictFormFieldUnmarshaller => FSFFU, _ }

    type SFU = FromEntityUnmarshaller[StrictForm]
    type FSFFOU[T] = Unmarshaller[Option[StrictForm.Field], T]

    protected def handleFieldResult[T](fieldName: String, result: Future[T]): Directive1[T] = onComplete(result).flatMap {
      case Success(x)                                  => provide(x)
      case Failure(Unmarshaller.NoContentException)    => reject(MissingFormFieldRejection(fieldName))
      case Failure(x: UnsupportedContentTypeException) => reject(UnsupportedRequestContentTypeRejection(x.supported, x.actualContentType))
      case Failure(x)                                  => reject(MalformedFormFieldRejection(fieldName, x.getMessage.nullAsEmpty, Option(x.getCause)))
    }

    private def strictFormUnmarshaller(ctx: RequestContext): SFU =
      StrictForm.unmarshaller(
        Unmarshaller.defaultUrlEncodedFormDataUnmarshaller,
        MultipartUnmarshallers.multipartFormDataUnmarshaller(ctx.log, ctx.parserSettings)
      )
    val stringFromStrictForm: FSFFU[String] = StrictForm.Field.unmarshaller(StrictForm.Field.FieldUnmarshaller.stringFieldUnmarshaller)

    def fieldOfForm[T](fieldName: String, fu: Unmarshaller[Option[StrictForm.Field], T]): RequestContext => Future[T] = { ctx =>
      import ctx.{ executionContext, materializer }
      strictFormUnmarshaller(ctx)(ctx.request.entity).fast.flatMap(form => fu(form field fieldName))
    }
    def filter[T](fieldName: String, fu: FSFFOU[T]): Directive1[T] =
      extract(fieldOfForm(fieldName, fu)).flatMap(r => handleFieldResult(fieldName, r))

    def repeatedFilter[T](fieldName: String, fu: FSFFU[T]): Directive1[Iterable[T]] =
      extract { ctx =>
        import ctx.{ executionContext, materializer }
        strictFormUnmarshaller(ctx)(ctx.request.entity).fast.flatMap(form => Future.sequence(form.fields.collect { case (`fieldName`, value) => fu(value) }))
      }.flatMap { result =>
        handleFieldResult(fieldName, result)
      }

    def requiredFilter[T](fieldName: String, fu: Unmarshaller[Option[StrictForm.Field], T], requiredValue: Any): Directive0 =
      extract(fieldOfForm(fieldName, fu)).flatMap {
        onComplete(_).flatMap {
          case Success(value) if value == requiredValue => pass
          case _                                        => reject
        }
      }
  }
}
