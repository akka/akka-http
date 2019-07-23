/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.annotation.InternalApi
import akka.http.impl.util._
import akka.http.scaladsl.model._

/** INTERNAL API */
@InternalApi
private[parser] trait CommonActions {

  def customMediaTypes: MediaTypes.FindCustom

  type StringMapBuilder = scala.collection.mutable.Builder[(String, String), Map[String, String]]

  def getMediaType(mainType: String, subType: String, charsetDefined: Boolean,
                   params: Map[String, String]): MediaType = {
    import MediaTypes._
    val subLower = subType.toRootLowerCase
    mainType.toRootLowerCase match {
      case "multipart" => subLower match {
        case "mixed"       => multipart.mixed(params)
        case "alternative" => multipart.alternative(params)
        case "related"     => multipart.related(params)
        case "form-data"   => multipart.`form-data`(params)
        case "signed"      => multipart.signed(params)
        case "encrypted"   => multipart.encrypted(params)
        case custom        => MediaType.customMultipart(custom, params)
      }
      case mainLower =>

        // Faster version of MediaType.withParams for the common case of empty params
        def withParams(mt: MediaType): MediaType = if (params.isEmpty) mt else mt.withParams(params)

        // Try user-defined function to get a MediaType
        customMediaTypes(mainLower, subLower) match {
          case Some(customMediaType) => withParams(customMediaType)
          case None =>
            // User-defined function didn't get a MediaType, check for a predefined value
            MediaTypes.getForKey((mainLower, subLower)) match {
              case Some(registered) => withParams(registered)
              case None =>
                // No predefined value, create custom MediaType
                if (charsetDefined)
                  MediaType.customWithOpenCharset(mainLower, subLower, params = params, allowArbitrarySubtypes = true)
                else
                  MediaType.customBinary(mainLower, subLower, MediaType.Compressible, params = params, allowArbitrarySubtypes = true)
            }
        }
    }
  }

  def getCharset(name: String): HttpCharset =
    HttpCharsets
      .getForKeyCaseInsensitive(name)
      .getOrElse(HttpCharset.custom(name))
}
