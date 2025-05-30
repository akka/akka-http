/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import java.nio.CharBuffer
import java.nio.charset.Charset
import java.text.{ DecimalFormat, DecimalFormatSymbols }
import java.util.Locale
import akka.annotation.InternalApi

import scala.annotation.tailrec
import scala.collection.{ LinearSeq, immutable }
import akka.parboiled2.{ CharPredicate, CharUtils }
import akka.http.impl.model.parser.CharacterClasses
import akka.http.scaladsl.model.HttpHeader
import akka.util.{ ByteString, ByteStringBuilder }

/**
 * INTERNAL API
 *
 * An entity that can render itself
 */
@InternalApi
private[http] trait Renderable {
  private[http] def render[R <: Rendering](r: R): r.type
}

/**
 * INTERNAL API
 *
 * An entity that can render itself and implements toString in terms of its rendering
 */
@InternalApi
private[http] trait ToStringRenderable extends Renderable {
  override def toString = render(new StringRendering).get
}

/**
 * INTERNAL API
 *
 * An entity that has a rendered value (like an HttpHeader)
 */
@InternalApi
private[http] trait ValueRenderable extends ToStringRenderable {
  def value: String = toString
}

/**
 * INTERNAL API
 *
 * An entity whose rendering result is cached in an unsynchronized and non-volatile lazy.
 */
@InternalApi
private[http] trait LazyValueBytesRenderable extends Renderable {
  // unsynchronized and non-volatile lazy init, worst case: we init once per core
  // which, since instances of derived classes are usually long-lived, is still better
  // that a synchronization overhead or even @volatile reads
  private[this] var _valueBytes: Array[Byte] = _
  private def valueBytes =
    if (_valueBytes != null) _valueBytes else { _valueBytes = value.asciiBytes; _valueBytes }

  def value: String
  def render[R <: Rendering](r: R): r.type = r ~~ valueBytes
  override def toString = value
}

/**
 * INTERNAL API
 *
 * An entity whose rendering result is determined eagerly at instantiation (and then is cached).
 * Useful for common predefined singleton values.
 */
@InternalApi
private[http] trait SingletonValueRenderable extends Product with Renderable {
  private[this] val valueBytes = value.asciiBytes
  def value = productPrefix
  def render[R <: Rendering](r: R): r.type = r ~~ valueBytes
}

/**
 * INTERNAL API
 *
 * A typeclass for rendering values.
 */
@InternalApi
private[http] trait Renderer[-T] {
  def render[R <: Rendering](r: R, value: T): r.type
}

private[http] object Renderer {
  implicit object CharRenderer extends Renderer[Char] {
    def render[R <: Rendering](r: R, value: Char): r.type = r ~~ value
  }
  implicit object IntRenderer extends Renderer[Int] {
    def render[R <: Rendering](r: R, value: Int): r.type = r ~~ value
  }
  implicit object StringRenderer extends Renderer[String] {
    def render[R <: Rendering](r: R, value: String): r.type = r ~~ value
  }
  implicit object ByteStringRenderer extends Renderer[ByteString] {
    def render[R <: Rendering](r: R, value: ByteString): r.type = r ~~ value
  }
  implicit object CharsRenderer extends Renderer[Array[Char]] {
    def render[R <: Rendering](r: R, value: Array[Char]): r.type = r ~~ value
  }
  object RenderableRenderer extends Renderer[Renderable] {
    def render[R <: Rendering](r: R, value: Renderable): r.type = value.render(r)
  }
  implicit def renderableRenderer[T <: Renderable]: Renderer[T] = RenderableRenderer

  def optionRenderer[D, T](defaultValue: D)(implicit sRenderer: Renderer[D], tRenderer: Renderer[T]): Renderer[Option[T]] =
    new Renderer[Option[T]] {
      def render[R <: Rendering](r: R, value: Option[T]): r.type =
        if (value.isEmpty) sRenderer.render(r, defaultValue) else tRenderer.render(r, value.get)
    }

  def defaultSeqRenderer[T: Renderer]: Renderer[immutable.Iterable[T]] =
    genericSeqRenderer[Renderable, T](Rendering.`, `, Rendering.Empty)
  def seqRenderer[T: Renderer](separator: String = ", ", empty: String = ""): Renderer[immutable.Iterable[T]] =
    genericSeqRenderer[String, T](separator, empty)
  def genericSeqRenderer[S, T](separator: S, empty: S)(implicit sRenderer: Renderer[S], tRenderer: Renderer[T]): Renderer[immutable.Iterable[T]] =
    new Renderer[immutable.Iterable[T]] {
      def render[R <: Rendering](r: R, value: immutable.Iterable[T]): r.type = {
        @tailrec def recI(values: IndexedSeq[T], ix: Int = 0): r.type =
          if (ix < values.size) {
            if (ix > 0) sRenderer.render(r, separator)
            tRenderer.render(r, values(ix))
            recI(values, ix + 1)
          } else r

        @tailrec def recL(remaining: LinearSeq[T]): r.type =
          if (remaining.nonEmpty) {
            if (remaining ne value) sRenderer.render(r, separator)
            tRenderer.render(r, remaining.head)
            recL(remaining.tail)
          } else r

        value match {
          case Nil              => r ~~ empty
          case x: IndexedSeq[T] => recI(x)
          case x: LinearSeq[T]  => recL(x)
          case _                => throw new IllegalStateException("Unexpected type value") // compiler completeness check pleaser
        }
      }
    }
}

/**
 * INTERNAL API
 *
 * The interface for a rendering sink. Implemented for several serialization targets.
 */
@InternalApi
private[http] trait Rendering {
  def ~~(ch: Char): this.type
  def ~~(bytes: Array[Byte]): this.type
  def ~~(bytes: ByteString): this.type

  def ~~(f: Float): this.type = this ~~ Rendering.floatFormat.format(f)
  def ~~(d: Double): this.type = this ~~ d.toString

  def ~~(i: Int): this.type = this ~~ i.toLong

  def ~~(l: Long): this.type = if (l != 0) this ~~ CharUtils.signedDecimalChars(l) else this ~~ '0'

  /**
   * Renders the given Int in (lower case) hex notation.
   */
  def ~~%(i: Int): this.type = this ~~% i.toLong

  /**
   * Renders the given Long in (lower case) hex notation.
   */
  def ~~%(lng: Long): this.type =
    if (lng != 0) {
      @tailrec def putChar(shift: Int): this.type = {
        this ~~ CharUtils.lowerHexDigit(lng >>> shift)
        if (shift > 0) putChar(shift - 4) else this
      }
      putChar((63 - java.lang.Long.numberOfLeadingZeros(lng)) & 0xFC)
    } else this ~~ '0'

  def ~~(string: String): this.type = {
    @tailrec def rec(ix: Int = 0): this.type =
      if (ix < string.length) { this ~~ string.charAt(ix); rec(ix + 1) } else this
    rec()
  }

  def ~~(chars: Array[Char]): this.type = {
    @tailrec def rec(ix: Int = 0): this.type =
      if (ix < chars.length) { this ~~ chars(ix); rec(ix + 1) } else this
    rec()
  }

  protected def mark: Int
  protected def check(mark: Int): Boolean
  /**
   * Renders a header safely, i.e. checking that it does not contain any CR of LF characters. If it does
   * the header should be discarded.
   */
  def ~~(header: HttpHeader): this.type = {
    val m = mark
    header.render(this)
    if (check(m)) this ~~ Rendering.CrLf
    else this
  }

  def ~~[T](value: T)(implicit ev: Renderer[T]): this.type = ev.render(this, value)

  /**
   * Renders the given string either directly (if it only contains token chars)
   * or in double quotes (if it is empty or contains at least one non-token char).
   */
  def ~~#(s: String): this.type =
    if (s.nonEmpty && CharacterClasses.tchar.matchesAll(s)) this ~~ s else ~~#!(s)

  /**
   * Renders the given string in double quotes.
   */
  def ~~#!(s: String): this.type = this.~~('"').putEscaped(s, Rendering.`\"`, '\\').~~('"')

  def putEscaped(s: String, escape: CharPredicate = Rendering.`\"`, escChar: Char = '\\'): this.type = {
    @tailrec def rec(ix: Int = 0): this.type =
      if (ix < s.length) {
        val c = s.charAt(ix)
        if (escape(c)) this ~~ escChar
        this ~~ c
        rec(ix + 1)
      } else this
    rec()
  }

  def putReplaced(s: String, keep: CharPredicate, placeholder: Char): this.type = {
    @tailrec def rec(ix: Int = 0): this.type =
      if (ix < s.length) {
        val c = s.charAt(ix)
        if (keep(c)) this ~~ c
        else this ~~ placeholder
        rec(ix + 1)
      } else this
    rec()
  }
}

private[http] object Rendering {
  val floatFormat = new DecimalFormat("0.0##", DecimalFormatSymbols.getInstance(Locale.ROOT))
  val `\"` = CharPredicate('\\', '"')

  // US-ASCII printable chars except for '"' and escape chars '\' and (for faulty clients) '%'
  // https://tools.ietf.org/html/rfc6266#appendix-D
  val contentDispositionFilenameSafeChars = CharPredicate.Printable -- "%\"\\"

  case object `, ` extends SingletonValueRenderable // default separator
  case object Empty extends Renderable {
    def render[R <: Rendering](r: R): r.type = r
  }

  case object CrLf extends Renderable {
    def render[R <: Rendering](r: R): r.type = r ~~ '\r' ~~ '\n'
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[http] class StringRendering extends Rendering {
  private[this] val sb = new java.lang.StringBuilder
  def ~~(ch: Char): this.type = { sb.append(ch); this }
  def ~~(bytes: Array[Byte]): this.type = {
    @tailrec def rec(ix: Int = 0): this.type =
      if (ix < bytes.length) { this ~~ bytes(ix).asInstanceOf[Char]; rec(ix + 1) } else this
    rec()
  }
  def ~~(bytes: ByteString): this.type = this ~~ bytes.toArray[Byte]
  def get: String = sb.toString

  override protected def mark: Int = sb.length()
  override protected def check(mark: Int): Boolean = {
    val origMark = mark

    @tailrec def rec(mark: Int): Boolean =
      if (mark < sb.length()) {
        val ch = sb.charAt(mark)
        if (ch == '\r' || ch == '\n') {
          sb.delete(origMark, sb.length())
          false
        } else rec(mark + 1)
      } else true

    rec(mark)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[http] class ByteArrayRendering(sizeHint: Int, logDiscardedHeader: String => Unit = _ => ()) extends Rendering {
  def this(sizeHint: Int) = this(sizeHint, _ => ())

  private[this] var array = new Array[Byte](sizeHint)

  private[this] var size = 0

  def get: Array[Byte] =
    if (size == array.length) array
    else java.util.Arrays.copyOfRange(array, 0, size)

  def ~~(char: Char): this.type = {
    val oldSize = growBy(1)
    array(oldSize) = char.toByte
    this
  }

  def ~~(bytes: Array[Byte]): this.type = {
    if (bytes.length > 0) {
      val oldSize = growBy(bytes.length)
      System.arraycopy(bytes, 0, array, oldSize, bytes.length)
    }
    this
  }

  def ~~(bytes: ByteString): this.type = {
    if (bytes.length > 0) {
      val oldSize = growBy(bytes.length)
      bytes.copyToArray(array, oldSize, bytes.length)
    }
    this
  }

  private def growBy(delta: Int): Int = {
    val oldSize = size
    val neededSize = oldSize.toLong + delta
    if (array.length < neededSize) {
      require(neededSize < Int.MaxValue, "Cannot create byte array greater than 2GB in size")
      val newLen = math.min(math.max(array.length.toLong << 1, neededSize), Int.MaxValue).toInt
      val newArray = new Array[Byte](newLen)
      System.arraycopy(array, 0, newArray, 0, array.length)
      array = newArray
    }
    size = neededSize.toInt
    oldSize
  }

  def remainingCapacity: Int = array.length - size
  def asByteString: ByteString = ByteString.ByteString1(array, 0, size)

  override protected def mark: Int = size
  override protected def check(mark: Int): Boolean = {
    val origMark = mark

    @tailrec def rec(mark: Int): Boolean =
      if (mark < size) {
        if (array(mark) == '\r' || array(mark) == '\n') {
          logDiscardedHeader("Invalid outgoing header was discarded. " + LogByteStringTools.printByteString(ByteString.fromArray(array, origMark, size - origMark)))
          size = origMark
          false
        } else rec(mark + 1)
      } else true

    rec(mark)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[http] class ByteStringRendering(sizeHint: Int, logDiscardedHeader: String => Unit = _ => ()) extends Rendering {
  def this(sizeHint: Int) = this(sizeHint, _ => ())

  private[this] val builder = new ByteStringBuilder
  builder.sizeHint(sizeHint)

  def get: ByteString = builder.result()

  def ~~(char: Char): this.type = {
    builder += char.toByte
    this
  }

  def ~~(bytes: Array[Byte]): this.type = {
    if (bytes.length > 0) builder.putByteArrayUnsafe(bytes)
    this
  }

  def ~~(bytes: ByteString): this.type = {
    if (bytes.length > 0) builder ++= bytes
    this
  }

  override protected def mark: Int = builder.length
  override protected def check(mark: Int): Boolean = {
    val origMark = mark
    val contents = builder.result()

    @tailrec def rec(mark: Int): Boolean =
      if (mark < builder.length) {
        val ch = contents(mark)
        if (ch == '\r' || ch == '\n') {
          logDiscardedHeader("Invalid outgoing header was discarded. " + LogByteStringTools.printByteString(contents.drop(origMark)))
          builder.clear()
          builder.append(contents.take(origMark))
          false
        } else rec(mark + 1)
      } else true

    rec(mark)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[http] class CustomCharsetByteStringRendering(nioCharset: Charset, sizeHint: Int) extends Rendering {
  private[this] val charBuffer = CharBuffer.allocate(64)
  private[this] val builder = new ByteStringBuilder
  builder.sizeHint(sizeHint)

  def get: ByteString = {
    flushCharBuffer()
    builder.result()
  }

  def ~~(char: Char): this.type = {
    if (!charBuffer.hasRemaining) flushCharBuffer()
    charBuffer.put(char)
    this
  }

  def ~~(bytes: Array[Byte]): this.type = {
    if (bytes.length > 0) {
      flushCharBuffer()
      builder.putByteArrayUnsafe(bytes)
    }
    this
  }

  def ~~(bytes: ByteString): this.type = {
    if (bytes.length > 0) {
      flushCharBuffer()
      builder ++= bytes
    }
    this
  }

  private def flushCharBuffer(): Unit = {
    charBuffer.flip()
    if (charBuffer.hasRemaining) {
      val byteBuffer = nioCharset.encode(charBuffer)
      // TODO: optimize by adding another `putByteArrayUnsafe` overload taking an byte array slice
      // and thus enabling `builder.putByteArrayUnsafe(byteBuffer.array(), 0, byteBuffer.remaining())`
      val bytes = new Array[Byte](byteBuffer.remaining())
      byteBuffer.get(bytes)
      builder.putByteArrayUnsafe(bytes)
    }
    charBuffer.clear()
  }

  override protected def mark: Int = {
    flushCharBuffer()
    builder.length
  }
  override protected def check(mark: Int): Boolean = {
    flushCharBuffer()

    val origMark = mark
    val contents = builder.result()

    @tailrec def rec(mark: Int): Boolean =
      if (mark < builder.length) {
        val ch = contents(mark)
        if (ch == '\r' || ch == '\n') {
          builder.clear()
          builder.append(contents.take(origMark))
          false
        } else rec(mark + 1)
      } else true

    rec(mark)
  }
}
