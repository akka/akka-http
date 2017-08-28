/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.model.*;
import akka.http.javadsl.unmarshalling.Unmarshaller;

public class JacksonXmlExampleTest extends JUnitRouteTest {

  @Test
  public void xmlUnmarshallerUsingJacksonModule() throws Exception {
    final String xml = "<point><x>3</x><y>4</y></point>";
    final Point point = new Point() {
      {
        setX(3);
        setY(4);
      }
    };

    {
      CompletionStage<Point> resultStage =
        JacksonXmlSupport.unmarshaller(Point.class).unmarshall(
          HttpEntities.create(ContentTypes.TEXT_XML_UTF8, xml),
          system().dispatcher(),
          materializer());

      assertEquals(point, resultStage.toCompletableFuture().get(3, TimeUnit.SECONDS));
    }

    {
      CompletionStage<Point> resultStage =
        JacksonXmlSupport.unmarshaller(Point.class).unmarshall(
          HttpEntities.create(ContentTypes.create(MediaTypes.APPLICATION_XML, HttpCharsets.UTF_8), xml),
          system().dispatcher(),
          materializer());

      assertEquals(point, resultStage.toCompletableFuture().get(3, TimeUnit.SECONDS));
    }
  }

  public static class Point {
    private int x, y;
    public void setX(int x) { this.x = x; }
    public int getX() { return this.x; }
    public void setY(int y) { this.y = y; }
    public int getY() { return this.y; }

    public String toString() {
      return "Point(x=" + x + ", y=" + y + ")";
    }

    public boolean equals(Object other) {
      Point that = other instanceof Point ? (Point) other : null;
      return that != null
          && that.x == this.x
          && that.y == this.y;
    }
  }
}
