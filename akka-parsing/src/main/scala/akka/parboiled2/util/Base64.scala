/*
 * Copyright 2009-2019 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.parboiled2.util

object Base64 {
  private var RFC2045: Base64 = _
  private var CUSTOM: Base64 = _

  def custom(): Base64 = {
    if (CUSTOM == null)
      CUSTOM = new Base64("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-_")
    CUSTOM
  }

  def rfc2045(): Base64 = {
    if (RFC2045 == null)
      RFC2045 = new Base64("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
    RFC2045
  }
}

class Base64(alphabet: String) {
  if (alphabet == null || alphabet.length() != 65)
    throw new IllegalArgumentException()

  val CA = alphabet.substring(0, 64).toCharArray
  val fillChar = alphabet.charAt(64)
  val IA: Array[Int] = Array.fill(256)(-1)

  (0 until CA.length).foreach(i => IA(CA(i)) = i)

  IA(fillChar) = 0

  def getAlphabet: Array[Char] =
    CA

  def decode(s: String): Array[Byte] = decode(s.toCharArray)

  /**
   * Decodes a BASE64 encoded char array. All illegal characters will be ignored and can handle both arrays with
   * and without line separators.
   *
   * @param sArr The source array. <code>null</code> or length 0 will return an empty array.
   * @return The decoded array of bytes. May be of length 0. Will be <code>null</code> if the legal characters
   *         (including '=') isn't divideable by 4.  (I.e. definitely corrupted).
   */
  def decode(sArr: Array[Char]): Array[Byte] = {
    // Check special case
    val sLen =
      if (sArr != null)
        sArr.length
      else
        0

    if (sLen == 0)
      return Array.empty[Byte]

    // Count illegal characters (including '\r', '\n') to know what size the returned array will be,
    // so we don't have to reallocate & copy it later.
    // If input is "pure" (I.e. no line separators or illegal chars) base64 this loop can be commented out.
    var sepCnt = 0 // Number of separator characters. (Actually illegal characters, but that's a bonus...)
    (0 until sLen).foreach { i =>
      if (IA(sArr(i)) < 0)
        sepCnt += 1
    }

    // Check so that legal chars (including '=') are evenly divideable by 4 as specified in RFC 2045.
    if ((sLen - sepCnt) % 4 != 0)
      return null

    var pad = 0
    var i = sLen - 1
    while (i > 0 && IA(sArr(i)) <= 0) {
      if (sArr(i) == fillChar)
        pad += 1
      i -= 1
    }

    val len = ((sLen - sepCnt) * 6 >> 3) - pad

    val dArr = Array.ofDim[Byte](len) // Preallocate byte[] of exact length
    var s = 0
    var d = 0
    while (d < len) {
      // Assemble three bytes into an int from four "valid" characters.
      var i = 0
      var j = 0

      // j only increased if a valid char was found.
      while (j < 4) {
        val c = IA(sArr(s))
        s += 1
        if (c >= 0)
          i |= c << (18 - j * 6)
        else
          j -= 1

        j += 1
      }

      // Add the bytes
      dArr(d) = (i >> 16).toByte
      d += 1
      if (d < len) {
        dArr(d) = (i >> 8).toByte
        d += 1
        if (d < len) {
          dArr(d) = i.toByte
          d += 1
        }
      }
    }

    dArr
  }

  def decodeFast(s: String): Array[Byte] = decodeFast(s.toCharArray)

  /**
   * Decodes a BASE64 encoded char array that is known to be resonably well formatted. The method is about twice as
   * fast as {@link #decode(char[])}. The preconditions are:<br>
   * + The array must have a line length of 76 chars OR no line separators at all (one line).<br>
   * + Line separator must be "\r\n", as specified in RFC 2045
   * + The array must not contain illegal characters within the encoded string<br>
   * + The array CAN have illegal characters at the beginning and end, those will be dealt with appropriately.<br>
   *
   * @param sArr The source array. Length 0 will return an empty array. <code>null</code> will throw an exception.
   * @return The decoded array of bytes. May be of length 0.
   */
  def decodeFast(sArr: Array[Char]): Array[Byte] = {
    // Check special case
    val sLen = sArr.length
    if (sLen == 0)
      return Array.empty[Byte]

    // Start and end index after trimming.
    var sIx = 0
    var eIx = sLen - 1

    // Trim illegal chars from start
    while (sIx < eIx && IA(sArr(sIx)) < 0)
      sIx += 1

    // Trim illegal chars from end
    while (eIx > 0 && IA(sArr(eIx)) < 0)
      eIx -= 1

    // get the padding count (=) (0, 1 or 2)
    // Count '=' at end.
    val pad =
      if (sArr(eIx) == fillChar)
        if (sArr(eIx - 1) == fillChar)
          2
        else
          1
      else
        0

    // Content count including possible separators
    val cCnt = eIx - sIx + 1

    // Count '=' at end.
    val sepCnt =
      if (sLen > 76)
        (if (sArr(76) == '\r')
          cCnt / 78
        else
          0) << 1
      else
        0

    val len = ((cCnt - sepCnt) * 6 >> 3) - pad // The number of decoded bytes
    val dArr = Array.ofDim[Byte](len); // Preallocate byte() of exact length

    // Decode all but the last 0 - 2 bytes.
    var d = 0
    var cc = 0
    val eLen = (len / 3) * 3

    while (d < eLen) {
      // Assemble three bytes into an int from four "valid" characters.
      var i = IA(sArr(sIx)) << 18
      sIx += 1
      i = i | IA(sArr(sIx)) << 12
      sIx += 1
      i = i | IA(sArr(sIx)) << 6
      sIx += 1
      i = i | IA(sArr(sIx))
      sIx += 1

      // Add the bytes
      dArr(d) = (i >> 16).toByte
      d += 1
      dArr(d) = (i >> 8).toByte
      d += 1
      dArr(d) = i.toByte
      d += 1

      // If line separator, jump over it.
      cc += 1
      if (sepCnt > 0 && cc == 19) {
        sIx += 2
        cc = 0
      }
    }

    if (d < len) {
      // Decode last 1-3 bytes (incl '=') into 1-3 bytes
      var i = 0
      var j = 0
      while (sIx <= eIx - pad) {
        i |= IA(sArr(sIx)) << (18 - j * 6)
        sIx += 1
        j += 1
      }

      var r = 16
      while (d < len) {
        dArr(d) = (i >> r).toByte
        d += 1
        r -= 8
      }
    }

    dArr
  }

  /**
   * Encodes a raw byte array into a BASE64 <code>String</code> representation in accordance with RFC 2045.
   *
   * @param sArr    The bytes to convert. If <code>null</code> or length 0 an empty array will be returned.
   * @param lineSep Optional "\r\n" after 76 characters, unless end of file.<br>
   *                No line separator will be in breach of RFC 2045 which specifies max 76 per line but will be a
   *                little faster.
   * @return A BASE64 encoded array. Never <code>null</code>.
   */
  def encodeToString(sArr: Array[Byte], lineSep: Boolean): String =
    // Reuse char[] since we can't create a String incrementally anyway and StringBuffer/Builder would be slower.
    new String(encodeToChar(sArr, lineSep))

  /**
   * Encodes a raw byte array into a BASE64 <code>char[]</code> representation i accordance with RFC 2045.
   *
   * @param sArr    The bytes to convert. If <code>null</code> or length 0 an empty array will be returned.
   * @param lineSep Optional "\r\n" after 76 characters, unless end of file.<br>
   *                No line separator will be in breach of RFC 2045 which specifies max 76 per line but will be a
   *                little faster.
   * @return A BASE64 encoded array. Never <code>null</code>.
   */
  def encodeToChar(sArr: Array[Byte], lineSep: Boolean): Array[Char] = {
    // Check special case
    val sLen =
      if (sArr != null)
        sArr.length
      else
        0

    if (sLen == 0)
      return Array.empty[Char]

    val eLen = (sLen / 3) * 3 // Length of even 24-bits.
    val cCnt = ((sLen - 1) / 3 + 1) << 2 // Returned character count

    // Length of returned array
    val dLen = cCnt + (if (lineSep == true)
      (cCnt - 1) / 76 << 1
    else
      0)

    val dArr = Array.ofDim[Char](dLen)

    // Encode even 24-bits
    var s = 0
    var d = 0
    var cc = 0
    while (s < eLen) {
      // Copy next three bytes into lower 24 bits of int, paying attension to sign.
      var i = (sArr(s) & 0xff) << 16
      s += 1
      i = i | ((sArr(s) & 0xff) << 8)
      s += 1
      i = i | (sArr(s) & 0xff)
      s += 1

      // Encode the int into four chars
      dArr(d) = CA((i >>> 18) & 0x3f)
      d += 1
      dArr(d) = CA((i >>> 12) & 0x3f)
      d += 1
      dArr(d) = CA((i >>> 6) & 0x3f)
      d += 1
      dArr(d) = CA(i & 0x3f)
      d += 1

      // Add optional line separator
      cc += 1
      if (lineSep && cc == 19 && d < dLen - 2) {
        dArr(d) = '\r'
        d += 1
        dArr(d) = '\n'
        d += 1
        cc = 0
      }
    }

    // Pad and encode last bits if source isn't even 24 bits.
    val left = sLen - eLen; // 0 - 2.
    if (left > 0) {
      // Prepare the int
      val i = ((sArr(eLen) & 0xff) << 10) | (if (left == 2)
        (sArr(sLen - 1) & 0xff) << 2
      else
        0)

      // Set last four chars
      dArr(dLen - 4) = CA(i >> 12)
      dArr(dLen - 3) = CA((i >>> 6) & 0x3f)
      dArr(dLen - 2) =
        if (left == 2)
          CA(i & 0x3f)
        else
          fillChar
      dArr(dLen - 1) = fillChar
    }

    dArr
  }
}
