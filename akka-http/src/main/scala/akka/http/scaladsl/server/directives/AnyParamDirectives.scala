/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.impl.util._
import akka.http.scaladsl.common._
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.scaladsl.util.FastFuture._

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

/**
 * Directives that can accept parameters either from the query string or from
 * form fields.
 *
 * Some field types will not be accepted if they are not applicable to both
 * query parameters and form fields.  E.g. ``anyParams('file.as[StrictForm.FileData])``
 * will fail because there is no way to accept file data as a query parameter.
 *
 * @groupname param AnyParam directives
 * @groupprio param 150
 */
trait AnyParamDirectives extends ToNameReceptacleEnhancements {
  import AnyParamDirectives._

  /**
   * Extracts the request's query parameters or form fields as a `Map[String, String]`.
   *
   * @group param
   */
  def anyParamMap: Directive1[Map[String, String]] = _anyParamMap

  /**
   * Extracts the request's query parameters or form fields as a `Map[String, List[String]]`.
   *
   * @group param
   */
  def anyParamMultiMap: Directive1[Map[String, List[String]]] = _anyParamMultiMap

  /**
   * Extracts the request's query parameters or form fields as a `Seq[(String, String)]`.
   *
   * @group param
   */
  def anyParamSeq: Directive1[immutable.Seq[(String, String)]] = _anyParamSeq

  /**
   * Extracts a query parameter or form field value from the request.
   * Rejects the request if the defined query parameter / form field matcher(s) don't match.
   *
   * Due to a bug in Scala 2.10, invocations of this method sometimes fail to compile with an
   * "too many arguments for method parameter" or "type mismatch" error.
   *
   * As a workaround add an `import ParameterDirectives.AnyParamMagnet` or use Scala 2.11.x.
   *
   * @group param
   */
  def anyParam(pdm: AnyParamMagnet): pdm.Out = pdm()

  /**
   * Extracts a number of query parameter or form field values from the request.
   * Rejects the request if the defined query parameter and form field matcher(s) don't match.
   *
   * Due to a bug in Scala 2.10, invocations of this method sometimes fail to compile with an
   * "too many arguments for method parameters" or "type mismatch" error.
   *
   * As a workaround add an `import ParameterDirectives.AnyParamMagnet` or use Scala 2.11.x.
   *
   * @group param
   */
  def anyParams(pdm: AnyParamMagnet): pdm.Out = pdm()

}

object AnyParamDirectives extends AnyParamDirectives with ParameterDirectives with FormFieldDirectives {

  private val _anyParamMap: Directive1[Map[String, String]] =
    for {
      p ← parameterMap
      f ← formFieldMap
    } yield p ++ f

  private val _anyParamMultiMap: Directive1[Map[String, List[String]]] =
    for {
      p ← parameterMultiMap
      f ← formFieldMultiMap
    } yield p ++ f

  private val _anyParamSeq: Directive1[immutable.Seq[(String, String)]] =
    for {
      p ← parameterSeq
      f ← formFieldSeq
    } yield p ++ f

  sealed trait AnyParamMagnet {
    type Out
    def apply(): Out
  }
  object AnyParamMagnet {
    implicit def apply[T](value: T)(implicit pdef: AnyParamDef[T]): AnyParamMagnet { type Out = pdef.Out } =
      new AnyParamMagnet {
        type Out = pdef.Out
        def apply() = pdef(value)
      }
  }

  type AnyParamDefAux[T, U] = AnyParamDef[T] { type Out = U }
  sealed trait AnyParamDef[T] {
    type Out
    def apply(value: T): Out
  }
  object AnyParamDef {
    def anyParamDef[A, B](f: A ⇒ B): AnyParamDefAux[A, B] =
      new AnyParamDef[A] {
        type Out = B
        def apply(value: A) = f(value)
      }

    import BasicDirectives._
    import RouteDirectives._
    import FutureDirectives._
    import akka.http.scaladsl.unmarshalling.{ FromStringUnmarshaller ⇒ FSU, _ }
    type SFU = FromEntityUnmarshaller[StrictForm]
    import akka.http.scaladsl.unmarshalling.{ FromStrictFormFieldUnmarshaller ⇒ FSFFU, _ }
    type FSFFOU[T] = Unmarshaller[Option[StrictForm.Field], T]
    type FSOU[T] = Unmarshaller[Option[String], T]

    private def extractAnyParam[A, B](f: A ⇒ Directive1[B]): AnyParamDefAux[A, Directive1[B]] = anyParamDef(f)
    private def handleAnyParamResult[T](paramName: String, result: Future[T])(implicit ec: ExecutionContext): Directive1[T] =
      onComplete(result).flatMap {
        case Success(x)                                  ⇒ provide(x)
        case Failure(Unmarshaller.NoContentException)    ⇒ reject(MissingAnyParamRejection(paramName))
        case Failure(x: UnsupportedContentTypeException) ⇒ reject(UnsupportedRequestContentTypeRejection(x.supported))
        case Failure(x)                                  ⇒ reject(MalformedQueryParamRejection(paramName, x.getMessage.nullAsEmpty, Option(x.getCause)))
      }

    //////////////////// "regular" parameter extraction //////////////////////

    private def fieldOfForm[T](fieldName: String, fu: Unmarshaller[Option[StrictForm.Field], T])(implicit sfu: SFU): RequestContext ⇒ Future[T] = { ctx ⇒
      import ctx.{ executionContext, materializer }
      sfu(ctx.request.entity).fast.flatMap(form ⇒ fu(form field fieldName))
    }

    private def filter[T](paramName: String, fsou: FSOU[T], fu: FSFFOU[T])(implicit sfu: SFU): Directive1[T] =
      extractRequestContext flatMap { ctx ⇒
        import ctx.executionContext
        import ctx.materializer
        Try(ctx.request.uri.query()) match {
          case Success(query) ⇒
            if (query.getAll(paramName).nonEmpty) handleAnyParamResult(paramName, fsou(query.get(paramName)))
            else handleAnyParamResult(paramName, fieldOfForm(paramName, fu)(sfu)(ctx))
          case Failure(t) ⇒ reject(MalformedRequestContentRejection(s"The request's query string is invalid: ${ctx.request.uri.rawQueryString.getOrElse("")}", t))
        }
      }

    private val fromStringUnmarshaller: FSU[String] = Unmarshaller.identityUnmarshaller[String]

    implicit def forString(implicit fu: FSFFU[String]): AnyParamDefAux[String, Directive1[String]] =
      extractAnyParam[String, String] { string ⇒ filter(string, fromStringUnmarshaller, fu) }
    implicit def forSymbol(implicit fu: FSFFU[String]): AnyParamDefAux[Symbol, Directive1[String]] =
      extractAnyParam[Symbol, String] { symbol ⇒ filter(symbol.name, fromStringUnmarshaller, fu) }
    implicit def forNR[T](implicit fsu: FSU[T], fu: FSFFU[T]): AnyParamDefAux[NameReceptacle[T], Directive1[T]] =
      extractAnyParam[NameReceptacle[T], T] { nr ⇒ filter(nr.name, fsu, fu) }
    implicit def forNUR[T]: AnyParamDefAux[NameUnmarshallerReceptacle[T], Directive1[T]] =
      extractAnyParam[NameUnmarshallerReceptacle[T], T] { nr ⇒ filter(nr.name, nr.um, StrictForm.Field.unmarshallerFromFSU(nr.um)) }
    implicit def forNOR[T](implicit fsou: FSOU[T], fu: FSFFOU[T]): AnyParamDefAux[NameOptionReceptacle[T], Directive1[Option[T]]] =
      extractAnyParam[NameOptionReceptacle[T], Option[T]] { nr ⇒ filter[Option[T]](nr.name, fsou, fu) }
    implicit def forNDR[T](implicit fsou: FSOU[T], fu: FSFFOU[T]): AnyParamDefAux[NameDefaultReceptacle[T], Directive1[T]] =
      extractAnyParam[NameDefaultReceptacle[T], T] { nr ⇒ filter[T](nr.name, fsou withDefaultValue nr.default, fu withDefaultValue nr.default) }
    implicit def forNOUR[T]: AnyParamDefAux[NameOptionUnmarshallerReceptacle[T], Directive1[Option[T]]] =
      extractAnyParam[NameOptionUnmarshallerReceptacle[T], Option[T]] { nr ⇒ filter(nr.name, nr.um: FSOU[T], (StrictForm.Field.unmarshallerFromFSU(nr.um): FSFFOU[T])) }
    implicit def forNDUR[T]: AnyParamDefAux[NameDefaultUnmarshallerReceptacle[T], Directive1[T]] =
      extractAnyParam[NameDefaultUnmarshallerReceptacle[T], T] { nr ⇒ filter[T](nr.name, (nr.um: FSOU[T]) withDefaultValue nr.default, (StrictForm.Field.unmarshallerFromFSU(nr.um): FSFFOU[T]) withDefaultValue nr.default) }

    //////////////////// required parameter support ////////////////////

    private def requiredFilter[T](paramName: String, fsou: FSOU[T], fu: Unmarshaller[Option[StrictForm.Field], T], requiredValue: Any): Directive0 =
      extractRequestContext flatMap { ctx ⇒
        import ctx.executionContext
        import ctx.materializer
        onComplete(fsou(ctx.request.uri.query().get(paramName))) flatMap {
          case Success(value) if (value == requiredValue) ⇒ pass
          case _ ⇒ extract(fieldOfForm(paramName, fu)).flatMap {
            onComplete(_).flatMap {
              case Success(value) if value == requiredValue ⇒ pass
              case _                                        ⇒ reject
            }
          }
        }
      }
    implicit def forRVR[T](implicit fsu: FSU[T], fu: FSFFU[T]): AnyParamDefAux[RequiredValueReceptacle[T], Directive0] =
      anyParamDef[RequiredValueReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, fsu, fu, rvr.requiredValue) }
    implicit def forRVDR[T]: AnyParamDefAux[RequiredValueUnmarshallerReceptacle[T], Directive0] =
      anyParamDef[RequiredValueUnmarshallerReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, rvr.um, StrictForm.Field.unmarshallerFromFSU(rvr.um), rvr.requiredValue) }

    //////////////////// repeated parameter support ////////////////////

    private def repeatedFilter[T](paramName: String, fsu: FSU[T], fu: FSFFU[T])(implicit sfu: SFU): Directive1[Iterable[T]] =
      extractRequestContext flatMap { ctx ⇒
        import ctx.executionContext
        import ctx.materializer
        if (ctx.request.uri.query().getAll(paramName).nonEmpty)
          handleAnyParamResult(paramName, Future.sequence(ctx.request.uri.query().getAll(paramName).map(fsu.apply)))
        else {
          val result = sfu(ctx.request.entity).fast.flatMap(form ⇒ Future.sequence(form.fields.collect { case (`paramName`, value) ⇒ fu(value) }))
          handleAnyParamResult(paramName, result)
        }
      }
    implicit def forRepVR[T](implicit fsu: FSU[T], fu: FSFFU[T]): AnyParamDefAux[RepeatedValueReceptacle[T], Directive1[Iterable[T]]] =
      extractAnyParam[RepeatedValueReceptacle[T], Iterable[T]] { rvr ⇒ repeatedFilter(rvr.name, fsu, fu) }
    implicit def forRepVDR[T]: AnyParamDefAux[RepeatedValueUnmarshallerReceptacle[T], Directive1[Iterable[T]]] =
      extractAnyParam[RepeatedValueUnmarshallerReceptacle[T], Iterable[T]] { rvr ⇒ repeatedFilter(rvr.name, rvr.um, StrictForm.Field.unmarshallerFromFSU(rvr.um)) }

    //////////////////// tuple support ////////////////////

    import akka.http.scaladsl.server.util.TupleOps._
    import akka.http.scaladsl.server.util.BinaryPolyFunc

    implicit def forTuple[T](implicit fold: FoldLeft[Directive0, T, ConvertAnyParamDefAndConcatenate.type]): AnyParamDefAux[T, fold.Out] =
      anyParamDef[T, fold.Out](fold(BasicDirectives.pass, _))

    object ConvertAnyParamDefAndConcatenate extends BinaryPolyFunc {
      implicit def from[P, TA, TB](implicit apd: AnyParamDefAux[P, Directive[TB]] /*{ type Out = Directive[TB] }*/ , ev: Join[TA, TB]): BinaryPolyFunc.Case[Directive[TA], P, ConvertAnyParamDefAndConcatenate.type] { type Out = Directive[ev.Out] } =
        at[Directive[TA], P] { (a, t) ⇒ a & apd(t) }
    }
  }
}
