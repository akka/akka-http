package docs

import java.lang.reflect.Method

import akka.annotation.ApiMayChange
import org.reflections.Reflections
import org.reflections.scanners.{MethodAnnotationsScanner, TypeAnnotationsScanner}
import org.reflections.util.{ClasspathHelper, ConfigurationBuilder}
import org.scalatest.{Matchers, WordSpec}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.Source

class ApiMayChangeDocCheckerSpec extends WordSpec with Matchers {

  def prettifyName(clazz: Class[_]): String = {
    clazz.getCanonicalName.replaceAll("\\$minus", "-").split("\\$")(0)
  }

  "ApiMayChange Docs" should {
    val reflections = new Reflections(new ConfigurationBuilder()
      .setUrls(ClasspathHelper.forPackage("akka.http"))
      .setScanners(
        new TypeAnnotationsScanner(),
        new MethodAnnotationsScanner()))
    val docPage = Source.fromFile("docs/src/main/paradox/compatibility-guidelines.md").getLines().toList
    "contain all ApiMayChange references in classes" in {
      val classes: mutable.Set[Class[_]] = reflections.getTypesAnnotatedWith(classOf[ApiMayChange], true).asScala
      val missing = classes.foldLeft(Set.empty[String])((set, clazz) => {
        val prettyName = prettifyName(clazz)
        if (docPage.exists(line => line.contains(prettyName)))
          set
        else
          set + prettyName
      })
      missing shouldBe Set.empty
    }
    "contain all ApiMayChange references in methods" in {
      val methods = reflections.getMethodsAnnotatedWith(classOf[ApiMayChange]).asScala
      val missing = methods
        .filterNot(removeClassesToIgnore)
        .foldLeft(Set.empty[String])((set, method) => {
          val prettyName = prettifyName(method.getDeclaringClass) + "#" + method.getName
          if (docPage.exists(line => line.contains(prettyName)))
            set
          else
            set + prettyName
        })
      missing shouldBe Set.empty
    }

  }

  // As Specs, Directives and HttpApp inherit get all directives methods, we skip those as they are not really bringing any extra info
  def removeClassesToIgnore(method: Method) = {
    Seq("Spec", ".Directives", ".HttpApp").exists(method.getDeclaringClass.getCanonicalName.contains)
  }
}
