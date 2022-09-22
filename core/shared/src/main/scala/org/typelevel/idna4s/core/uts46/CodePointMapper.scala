/*
 * Copyright (c) 2022 Typelevel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.typelevel.idna4s.core.uts46

import cats._
import cats.collections.BitSet
import cats.data._
import cats.syntax.all._
import java.lang.StringBuilder
import org.typelevel.idna4s.core._
import scala.annotation.tailrec
import scala.collection.immutable.IntMap
import scala.util.control.NoStackTrace

/**
 * This is a base type which exists for the generated code.
 */
abstract private[uts46] class CodePointMapperBase {
  protected def validAlways: BitSet

  protected def validNV8: BitSet

  protected def validXV8: BitSet

  protected def ignored: BitSet

  protected def mappedMultiCodePoints: IntMap[NonEmptyList[Int]]

  protected def deviationMapped: IntMap[Int]

  protected def deviationMultiMapped: IntMap[NonEmptyList[Int]]

  protected def disallowed: BitSet

  protected def disallowedSTD3Valid: BitSet

  protected def disallowedSTD3Mapped: IntMap[Int]

  protected def disallowedSTD3MultiMapped: IntMap[NonEmptyList[Int]]

  protected def deviationIgnored: BitSet

  protected def mapped: IntMap[Int]

  /**
   * The Unicode version the code point mapping functions target.
   *
   * @note
   *   UTS-46 is backwards compatible with future versions of Unicode. The only part of the
   *   mapping operation which can change between Unicode revisions is the disallowed character
   *   set. This includes the STD3 Valid and STD3 mapped variants. In other words, code points
   *   which were disallowed by some prior Unicode version may be made allowed in some future
   *   Unicode version or they may still be disallowed but have their mappings under STD3
   *   changed or they may be considered ''valid under STD3 only'', when they were formerlly
   *   disllowed under STD3.
   */
  def unicodeVersion: String
}

object CodePointMapper extends GeneratedCodePointMapper {

  /**
   * Map the code points in the given `String` according to the UTS-46 mapping tables as
   * described in section 5 of UTS-46. useStd3ASCIIRules is true and transitionalProcessing is
   * false.
   */
  def mapCodePoints(input: String): Either[MappingException, String] =
    mapCodePoints(true, false)(input)

  /**
   * Map the code points in the given `String` according to the UTS-46 mapping tables as
   * described in section 5 of UTS-46. This is step 1 of processing an input `String` according
   * to UTS-46 for IDNA compatibility.
   *
   * @param useStd3ASCIIRules
   *   Whether or not to use STD3 ASCII rules. UTS-46 strongly recommends this be `true`.
   * @param transitionalProcessing
   *   Determines if the deviation characters should be mapped or considered valid. From UTS-46,
   *   "Transitional Processing should only be used immediately before a DNS lookup in the
   *   circumstances where the registry does not guarantee a strategy of bundling or blocking.
   *   Nontransitional Processing, which is fully compatible with IDNA2008, should be used in
   *   all other cases.
   *
   * @see
   *   [[https://www.unicode.org/reports/tr46/#IDNA_Mapping_Table UTS-46 Section 5]]
   */
  def mapCodePoints(useStd3ASCIIRules: Boolean, transitionalProcessing: Boolean)(
      input: String): Either[MappingException, String] = {
    val len: Int = input.length

    @tailrec
    def loop(
        acc: StringBuilder,
        errors: List[CodePointMappingException],
        index: Int): Either[MappingException, String] =
      if (index >= len) {
        NonEmptyList
          .fromList(errors)
          .fold(
            Right(acc.toString): Either[MappingException, String]
          )(errors => Left(MappingException(errors, acc.toString)))
      } else {
        val value: Int = input.codePointAt(index)
        val indexIncrement: Int = if (value >= 0x10000) 2 else 1

        // We check valid then mapped first. This is for two reasons. First,
        // we want to prioritize fast execution on the successful code
        // path. Second, other than disallowed, valid/mapped make up the vast
        // majority of the domain.

        if (validAlways(value) || validNV8(value) || validXV8(value)) {
          // VALID
          loop(acc.appendCodePoint(value), errors, index + indexIncrement)
        } else if (mapped.contains(value)) {
          // MAPPED
          loop(acc.appendCodePoint(mapped(value)), errors, index + indexIncrement)
        } else if (mappedMultiCodePoints.contains(value)) {
          // MAPPED MULTI
          loop(
            mappedMultiCodePoints(value).foldLeft(acc) {
              case (acc, value) =>
                acc.appendCodePoint(value)
            },
            errors,
            index + indexIncrement)
        } else if (disallowed(value)) {
          // DISALLOWED
          loop(
            acc.appendCodePoint(ReplacementCharacter),
            CodePointMappingException(
              index,
              "Disallowed code point in input.",
              CodePoint.unsafeFromInt(value)) :: errors,
            index + indexIncrement
          )
        } else if (ignored(value)) {
          // IGNORED
          loop(acc, errors, index + indexIncrement)
        } else if (deviationMapped.contains(value)) {
          // DEVIATION
          if (transitionalProcessing) {
            loop(acc.appendCodePoint(deviationMapped(value)), errors, index + indexIncrement)
          } else {
            loop(acc.appendCodePoint(value), errors, index + indexIncrement)
          }
        } else if (deviationMultiMapped.contains(value)) {
          // DEVIATION_MULTI
          if (transitionalProcessing) {
            loop(
              deviationMultiMapped(value).foldLeft(acc) {
                case (acc, value) =>
                  acc.appendCodePoint(value)
              },
              errors,
              index + indexIncrement)
          } else {
            loop(acc.appendCodePoint(value), errors, index + indexIncrement)
          }
        } else if (disallowedSTD3Valid(value)) {
          // DISALLOWED_STD3_VALID
          if (useStd3ASCIIRules) {
            loop(
              acc.appendCodePoint(ReplacementCharacter),
              CodePointMappingException(
                index,
                "Disallowed code point in input.",
                CodePoint.unsafeFromInt(value)) :: errors,
              index + indexIncrement
            )
          } else {
            loop(acc.appendCodePoint(value), errors, index + indexIncrement)
          }
        } else if (disallowedSTD3Mapped.contains(value)) {
          // DISALLOWED_STD3_MAPPED
          if (useStd3ASCIIRules) {
            loop(
              acc.appendCodePoint(ReplacementCharacter),
              CodePointMappingException(
                index,
                "Disallowed code point in input.",
                CodePoint.unsafeFromInt(value)) :: errors,
              index + indexIncrement
            )
          } else {
            loop(
              acc.appendCodePoint(disallowedSTD3Mapped(value)),
              errors,
              index + indexIncrement)
          }
        } else if (disallowedSTD3MultiMapped.contains(value)) {
          // DISALLOWED_STD3_MAPPED_MULTI
          if (useStd3ASCIIRules) {
            loop(
              acc.appendCodePoint(ReplacementCharacter),
              CodePointMappingException(
                index,
                "Disallowed code point in input.",
                CodePoint.unsafeFromInt(value)) :: errors,
              index + indexIncrement
            )
          } else {
            loop(
              disallowedSTD3MultiMapped(value).foldLeft(acc) {
                case (acc, value) =>
                  acc.appendCodePoint(value)
              },
              errors,
              index + indexIncrement)
          }
        } else if (deviationIgnored(value)) {
          // DEVIATION_IGNORED
          loop(acc, errors, index + indexIncrement)
        } else {
          // Should be impossible
          throw new AssertionError(
            s"Code point does not map to any set (this is probably a bug in idna4s): $value"
          )
        }
      }

    loop(new StringBuilder(len), Nil, 0)
  }

  /**
   * Determine the mapping of a code point according to the UTS-46 mapping tables as described
   * in section 5 of UTS-46.
   *
   * This is functionally the same as [[#mapCodePoints]] and one could even use this to write
   * [[#mapCodePoints]], however [[#mapCodePoints]] is optimized for bulk mapping a full
   * `String` and one should prefer that method for most use cases.
   *
   * @see
   *   [[https://www.unicode.org/reports/tr46/#IDNA_Mapping_Table UTS-46 Section 5]]
   */
  def mapCodePoint(codePoint: CodePoint): CodePointStatus = {
    import CodePointStatus._

    val value: Int = codePoint.value

    if (validAlways(value)) {
      Valid.always
    } else if (validNV8(value)) {
      Valid.nv8
    } else if (validXV8(value)) {
      Valid.xv8
    } else if (mapped.contains(value)) {
      Mapped.one(CodePoint.unsafeFromInt(mapped(value)))
    } else if (mappedMultiCodePoints.contains(value)) {
      Mapped.of(mappedMultiCodePoints(value).map(CodePoint.unsafeFromInt))
    } else if (ignored(value)) {
      Ignored.instance
    } else if (deviationMapped.contains(value)) {
      Deviation.one(CodePoint.unsafeFromInt(deviationMapped(value)))
    } else if (deviationMultiMapped.contains(value)) {
      Deviation.of(deviationMultiMapped(codePoint.value).map(CodePoint.unsafeFromInt).toList)
    } else if (disallowed(value)) {
      Disallowed.instance
    } else if (disallowedSTD3Valid(value)) {
      Disallowed_STD3_Valid.instance
    } else if (disallowedSTD3Mapped.contains(value)) {
      Disallowed_STD3_Mapped.one(CodePoint.unsafeFromInt(disallowedSTD3Mapped(value)))
    } else if (disallowedSTD3MultiMapped.contains(value)) {
      Disallowed_STD3_Mapped.of(disallowedSTD3MultiMapped(value).map(CodePoint.unsafeFromInt))
    } else if (deviationIgnored(value)) {
      Deviation.ignored
    } else {
      // Impossible

      throw new AssertionError(
        s"Code point does not map to any set (this is probably a bug in idna4s): $value"
      )
    }
  }

  /**
   * As [[#mapIntCodePoint]], but throws an exception if the input is not a valid Unicode code
   * point.
   */
  def unsafeMapIntCodePoint(codePoint: Int): CodePointStatus =
    mapCodePoint(CodePoint.unsafeFromInt(codePoint))

  /**
   * As [[#mapCodePoint]], but takes an arbitrary `Int` value. This will fail the `Int` is not a
   * valid Unicode code point.
   */
  def mapIntCodePoint(codePoint: Int): Either[String, CodePointStatus] =
    Either.catchNonFatal(unsafeMapIntCodePoint(codePoint)).leftMap(_.getLocalizedMessage)

  /**
   * An error that is yielded when mapping an individual code point fails.
   */
  sealed abstract class CodePointMappingException extends RuntimeException with NoStackTrace {

    /**
     * The index of the start of the Unicode code point in the input where the failure occurred.
     */
    def failureIndex: Int

    /**
     * A description of why the failure occurred.
     */
    def message: String

    /**
     * The [[CodePoint]] which caused the failure.
     */
    def codePoint: CodePoint

    // final

    final override def getMessage: String =
      toString

    final override def toString: String =
      s"CodePointMappingException(message = $message, failureIndex = $failureIndex, codePoint = $codePoint)"
  }

  object CodePointMappingException {
    final private[this] case class CodePointMappingExceptionImpl(
        override val failureIndex: Int,
        override val message: String,
        override val codePoint: CodePoint)
        extends CodePointMappingException

    def apply(
        failureIndex: Int,
        message: String,
        codePoint: CodePoint): CodePointMappingException =
      CodePointMappingExceptionImpl(failureIndex, message, codePoint)

    implicit val hashAndOrderForCodePointMappingException
        : Hash[CodePointMappingException] with Order[CodePointMappingException] =
      new Hash[CodePointMappingException] with Order[CodePointMappingException] {
        override def hash(x: CodePointMappingException): Int = x.hashCode

        override def compare(x: CodePointMappingException, y: CodePointMappingException): Int =
          x.failureIndex.compare(y.failureIndex) match {
            case 0 =>
              x.message.compare(y.message) match {
                case 0 =>
                  x.codePoint.compare(y.codePoint)
                case otherwise => otherwise
              }
            case otherwise => otherwise
          }
      }

    implicit val showForCodePointMappingException: Show[CodePointMappingException] =
      Show.fromToString
  }

  /**
   * An error which is yielded if attempting to map a sequence of Unicode code points with the
   * UTS-46 mapping algorithm fails.
   */
  sealed abstract class MappingException extends RuntimeException with NoStackTrace {

    /**
     * One or more mapping [[CodePointMappingException]].
     *
     * Each [[CodePointMappingException]] describes the mapping failure of an independent code
     * point.
     */
    def errors: NonEmptyList[CodePointMappingException]

    /**
     * The input string, mapped as much as was possible. Code points which failed replaced with
     * the Unicode replacement character � (0xFFFD). This is mandated by UTS-46.
     */
    def partiallyMappedInput: String

    /**
     * Reconstruct the original input `String` from [[#errors]] and [[#partiallyMappedInput]].
     *
     * @note
     *   The output of this should ''not'' be directly rendered to the user or in error
     *   messages. The reason that UTS-46 mandates that the replacement character � be inserted
     *   into `String` values which fail to map is that their rendering can be misleading to the
     *   user.
     *
     * @note
     *   If this value contains the Unicode replacement character �, that means that � was
     *   present in the original input `String`.
     */
    final lazy val unsafeOriginalInputString: String = {
      def replaceError(sb: StringBuilder, error: CodePointMappingException): StringBuilder = {
        val failureIndex: Int = error.failureIndex

        sb.replace(
          failureIndex,
          failureIndex + 1,
          new String(Character.toChars(error.codePoint.value)))
      }

      errors
        .reduceLeftTo(
          replaceError(new StringBuilder(partiallyMappedInput), _)
        )(replaceError)
        .toString
    }

    final override def getMessage: String =
      toString

    final override def toString: String =
      s"MappingException(errors = ${errors}, partiallyMappedInput = ${partiallyMappedInput})"
  }

  object MappingException {
    final private[this] case class MappingExceptionImpl(
        override val errors: NonEmptyList[CodePointMappingException],
        override val partiallyMappedInput: String)
        extends MappingException

    def apply(
        errors: NonEmptyList[CodePointMappingException],
        partiallyMappedInput: String): MappingException =
      MappingExceptionImpl(errors, partiallyMappedInput)

    implicit val hashAndOrderForMappingException
        : Hash[MappingException] with Order[MappingException] =
      new Hash[MappingException] with Order[MappingException] {
        override def hash(x: MappingException): Int =
          x.hashCode

        override def compare(x: MappingException, y: MappingException): Int =
          x.errors.compare(y.errors) match {
            case 0 =>
              x.partiallyMappedInput.compare(y.partiallyMappedInput)
            case otherwise => otherwise
          }
      }
  }

  /**
   * A constant for the Unicode replacement character �.
   */
  final private val ReplacementCharacter =
    0xfffd
}