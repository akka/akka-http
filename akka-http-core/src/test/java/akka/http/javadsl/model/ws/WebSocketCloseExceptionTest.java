/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.model.ws;

import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import static org.junit.Assert.assertEquals;

public class WebSocketCloseExceptionTest extends JUnitSuite {

  @Test
  public void createWithCodeAndReason() {
    WebSocketCloseException e = WebSocketCloseException.create(4001, "go away");
    assertEquals(4001, e.closeCode());
    assertEquals("go away", e.closeReason());
  }

  @Test
  public void createWithCode() {
    WebSocketCloseException e = WebSocketCloseException.create(3000);
    assertEquals(3000, e.closeCode());
    assertEquals("", e.closeReason());
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectInvalidCode() {
    WebSocketCloseException.create(1000, "regular");
  }
}
