/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import java.io.{ OutputStream, InputStream }

class NoCodingSpec extends CoderSpec {
  protected def Coder: Coder = Coders.NoCoding

  override protected def corruptInputCheck = false

  protected def newEncodedOutputStream(underlying: OutputStream): OutputStream = underlying
  protected def newDecodedInputStream(underlying: InputStream): InputStream = underlying
}
