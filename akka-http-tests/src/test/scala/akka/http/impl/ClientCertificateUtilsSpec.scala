/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ClientCertificateUtilsSpec extends AnyWordSpec with Matchers {

  // These are possible valid RFC2553 names, but, passing them through the JDK X500Name parser/renderer
  // will actually give us the set below. Using the internal X500Name class is messy though,  just shown here as an
  // example for generating more in case of problematic dnames ones found in the wild
  /*
  Seq(
    "CN=Steve Kille,O=Isode Limited,C=GB",
    "OU=Sales+CN=J. Smith,O=Widget Inc.,C=US",
    "CN=L. Eagle,O=Sue\\, Grabbit and Runn,C=GB",
    "CN=Before\nAfter,O=Test,C=GB",
    "OU=Sales + CN=J. Smith, O=Widget Inc., C=US",
    "O=anvils, O=acme, C=US, CN=Wiley Coyote",
    "CN=Steve Kille , O=Isode Limited,C=GB",
    """CN=Vision\\Devision , O=Isode Limited,C=GB""",
    """CN="Vision,,Devision", O=Isode Limited,C=GB"""
  ).foreach(dname => println(new sun.security.x509.X500Name(dname).getRFC2253Name))
*/

  val javax500RenderedDnamesAndCnames = Seq(
    "CN=Steve Kille,O=Isode Limited,C=GB" -> "Steve Kille",
    "OU=Sales+CN=J. Smith,O=Widget Inc.,C=US" -> "J. Smith",
    """CN=L. Eagle,O=Sue\, Grabbit and Runn,C=GB""" -> "L. Eagle",
    "CN=Before\nAfter,O=Test,C=GB" -> "Before\nAfter",
    "OU=Sales+CN=J. Smith,O=Widget Inc.,C=US" -> "J. Smith",
    "O=anvils,O=acme,C=US,CN=Wiley Coyote" -> "Wiley Coyote",
    "CN=Steve Kille,O=Isode Limited,C=GB" -> "Steve Kille",
    """CN=Vision\\Devision,O=Isode Limited,C=GB""" -> """Vision\\Devision""",
    """CN=Vision\,\,Devision,O=Isode Limited,C=GB""" -> """Vision\,\,Devision"""
  )

  def parseJavaxX500NameRendering(dname: String): Option[String] =
    ClientCertificateUtils.extractCN(new sun.security.x509.X500Name(dname).getRFC2253Name)

  "The RFC2553 parser" should {

    "parse names" in {
      javax500RenderedDnamesAndCnames.foreach {
        case (dname, expectedCname) =>
          withClue(dname) {
            ClientCertificateUtils.extractCN(dname) shouldBe (Some(expectedCname))
          }

      }
    }
  }

}
