/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import java.util.OptionalLong

import akka.http.impl.util.{ Rendering, ValueRenderable }
import akka.http.javadsl.{ model => jm }

sealed abstract class ByteRange extends jm.headers.ByteRange with ValueRenderable {
  /** Java API */
  def getSliceFirst: OptionalLong = OptionalLong.empty
  /** Java API */
  def getSliceLast: OptionalLong = OptionalLong.empty
  /** Java API */
  def getOffset: OptionalLong = OptionalLong.empty
  /** Java API */
  def getSuffixLength: OptionalLong = OptionalLong.empty

  /** Java API */
  def isSlice: Boolean = false

  /** Java API */
  def isFromOffset: Boolean = false

  /** Java API */
  def isSuffix: Boolean = false
}

object ByteRange {
  def apply(first: Long, last: Long) = Slice(first, last)
  def fromOffset(offset: Long) = FromOffset(offset)

  /** Constructs a range that spans the last `length` bytes of an entity. */
  def suffix(length: Long): Suffix = Suffix(length)

  final case class Slice(first: Long, last: Long) extends ByteRange {
    require(0 <= first && first <= last, "first must be >= 0 and <= last")
    def render[R <: Rendering](r: R): r.type = r ~~ first ~~ '-' ~~ last

    /** Java API */
    override def isSlice: Boolean = true
    /** Java API */
    override def getSliceFirst: OptionalLong = OptionalLong.of(first)
    /** Java API */
    override def getSliceLast: OptionalLong = OptionalLong.of(last)
  }

  final case class FromOffset(offset: Long) extends ByteRange {
    require(0 <= offset, "offset must be >= 0")
    def render[R <: Rendering](r: R): r.type = r ~~ offset ~~ '-'

    /** Java API */
    override def isFromOffset: Boolean = true
    /** Java API */
    override def getOffset: OptionalLong = OptionalLong.of(offset)
  }

  /**
   * Used to specify the last `length` bytes of an entity. If the entity is shorter than the given `length`, then the
   * range spans the entire entity.
   */
  final case class Suffix(length: Long) extends ByteRange {
    require(0 <= length, "length must be >= 0")
    def render[R <: Rendering](r: R): r.type = r ~~ '-' ~~ length

    /** Java API */
    override def isSuffix: Boolean = true
    /** Java API */
    override def getSuffixLength: OptionalLong = OptionalLong.of(length)
  }
}
