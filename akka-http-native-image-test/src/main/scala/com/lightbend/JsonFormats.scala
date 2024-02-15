package com.lightbend

import com.lightbend.UserRegistry.ActionPerformed
import spray.json.AdditionalFormats
import spray.json.ProductFormats
import spray.json.StandardFormats

//#json-formats
import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat

object JsonFormats extends AdditionalFormats with StandardFormats with ProductFormats {
  // import the default encoders for primitive types (Int, String, Lists etc)
  import DefaultJsonProtocol._

  // Note: spray-json uses reflection to extract the field names so these types require explicit reflection metadata
  implicit val userJsonFormat: RootJsonFormat[User] = jsonFormat3(User.apply)

  implicit val usersJsonFormat: RootJsonFormat[Users] = jsonFormat1(Users.apply)

  implicit val actionPerformedJsonFormat: RootJsonFormat[ActionPerformed]  = jsonFormat1(ActionPerformed.apply)
}

