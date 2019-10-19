/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http

/**
 * Helper that creates strings that all share the same hashCode == 0.
 *
 * Adapted from MIT-licensed code by Andriy Plokhotnyuk
 * at https://github.com/plokhotnyuk/jsoniter-scala/blob/26b5ecdd4f8c2ab7e97bd8106cefdda4c1e701ce/jsoniter-scala-benchmark/src/main/scala/com/github/plokhotnyuk/jsoniter_scala/macros/HashCodeCollider.scala#L6.
 */
object HashCodeCollider {
  val visibleChars = (33 until 127).filterNot(c => c == '\\' || c == '"')
  def asciiChars: Iterator[Int] = visibleChars.toIterator
  def asciiCharsAndHash(previousHash: Int): Iterator[(Int, Int)] = visibleChars.toIterator.map(c => c -> (previousHash + c) * 31)

  /** Creates an iterator of Strings that all have hashCode == 0 */
  def zeroHashCodeIterator(): Iterator[String] =
    for {
      (i0, h0) <- asciiCharsAndHash(0)
      (i1, h1) <- asciiCharsAndHash(h0)
      (i2, h2) <- asciiCharsAndHash(h1) if (((h2 + 32) * 923521) ^ ((h2 + 127) * 923521)) < 0
      (i3, h3) <- asciiCharsAndHash(h2) if (((h3 + 32) * 29791) ^ ((h3 + 127) * 29791)) < 0
      (i4, h4) <- asciiCharsAndHash(h3) if (((h4 + 32) * 961) ^ ((h4 + 127) * 961)) < 0
      (i5, h5) <- asciiCharsAndHash(h4) if (((h5 + 32) * 31) ^ ((h5 + 127) * 31)) < 0
      (i6, h6) <- asciiCharsAndHash(h5) if ((h6 + 32) ^ (h6 + 127)) < 0
      (i7, h7) <- asciiCharsAndHash(h6) if h6 + i7 == 0
    } yield new String(Array(i0, i1, i2, i3, i4, i5, i6, i7).map(_.toChar))
}
