/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.net.{ URL, URLDecoder, URLEncoder }

import akka.stream.ActorMaterializer
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.testkit.AkkaSpec
import org.scalatest.{ MustMatchers, WordSpec }

import scala.util.Try

class FormDataSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val formData = FormData(Map("surname" → "Smith", "age" → "42"))

  "The FormData infrastructure" should {
    "properly round-trip the fields of www-urlencoded forms" in {
      Marshal(formData).to[HttpEntity]
        .flatMap(Unmarshal(_).to[FormData]).futureValue shouldEqual formData
    }

    "properly marshal www-urlencoded forms containing special chars" in {
      Marshal(FormData(Map("name" → "Smith&Wesson"))).to[HttpEntity]
        .flatMap(Unmarshal(_).to[String]).futureValue shouldEqual "name=Smith%26Wesson"

      Marshal(FormData(Map("name" → "Smith+Wesson; hopefully!"))).to[HttpEntity]
        .flatMap(Unmarshal(_).to[String]).futureValue shouldEqual "name=Smith%2BWesson%3B+hopefully%21"
    }
  }

}

class UriSpec extends WordSpec with MustMatchers {
  val userInfo = "user:p%40ssword"
  val hostAndPath = "host/direc%20tory/fi%20le.tgz?query=test%25#frag%20ment"
  val uriString = s"https://$userInfo@$hostAndPath"

  "A Java URI" must {
    "convert into an Akka HTTP Uri" in {
      import java.net.{ URI ⇒ JavaURI }

      info("              string: " + uriString)
      val javaUri = JavaURI.create(uriString)
      info("                java: " + javaUri)

      val decoded = javaUri.toASCIIString
      info("  toASCIIString java: " + decoded)

      //            val akkaUri = Uri(javaUri.toString)
      val akkaFromInitial = Uri(uriString)
      info("   from initial akka: " + akkaFromInitial)

      def akkaFromAscii = Uri(javaUri.toASCIIString)
      info("from java ASCII akka: " + Try(akkaFromAscii))

      def akkaUrifromJavaUri(u: JavaURI): Uri = {
        def port(i: Int) = i match {
          case -1 ⇒ 0
          case _  ⇒ i
        }

        Uri.apply(
          javaUri.getScheme,
          Uri.Authority(Uri.Host(javaUri.getHost), port(javaUri.getPort), javaUri.getUserInfo),
          Uri.Path(javaUri.getPath),
          None,
          Option(javaUri.getFragment)
        ).withQuery(Uri.Query(javaUri.getRawQuery))
      }

      info("        manual akka: " + Try(akkaUrifromJavaUri(javaUri)))

      akkaUrifromJavaUri(javaUri).toString mustEqual javaUri.toString
    }
  }
}
