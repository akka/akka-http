/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

/*
 * Adapted from github.com/twitter/hpack with this license:
 *
 * Copyright 2014 Twitter, Inc.
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

package akka.http.shaded.com.twitter.hpack;

import java.io.IOException;
import java.io.OutputStream;

final class HuffmanEncoder {

  private final int[] codes;
  private final byte[] lengths;

  /**
   * Creates a new Huffman encoder with the specified Huffman coding.
   * @param codes   the Huffman codes indexed by symbol
   * @param lengths the length of each Huffman code
   */
  HuffmanEncoder(int[] codes, byte[] lengths) {
    this.codes = codes;
    this.lengths = lengths;
  }

  /**
   * Compresses the input string literal using the Huffman coding.
   * @param  out  the output stream for the compressed data
   * @param  data the string literal to be Huffman encoded
   * @param  off  the start offset in the data
   * @param  len  the number of bytes to encode
   * @throws IOException if an I/O error occurs. In particular,
   *         an <code>IOException</code> may be thrown if the
   *         output stream has been closed.
   */
  public void encode(OutputStream out, String string) throws IOException {
    if (out == null) {
      throw new NullPointerException("out");
    } else if (string == null) {
      throw new NullPointerException("string");
    }

    long current = 0;
    int n = 0;
    int len = string.length();

    for (int i = 0; i < len; i++) {
      int b = string.charAt(i) & 0xFF;
      int code = codes[b];
      int nbits = lengths[b];

      current <<= nbits;
      current |= code;
      n += nbits;

      while (n >= 8) {
        n -= 8;
        out.write(((int)(current >> n)));
      }
    }

    if (n > 0) {
      current <<= (8 - n);
      current |= (0xFF >>> n); // this should be EOS symbol
      out.write((int)current);
    }
  }

  /**
   * Returns the number of bytes required to Huffman encode the input string literal.
   * @param  data the string literal to be Huffman encoded
   * @return the number of bytes required to Huffman encode <code>data</code>
   */
  public int getEncodedLength(String data) {
    if (data == null) {
      throw new NullPointerException("data");
    }
    long len = 0;
    for (int i = 0; i < data.length(); i++) {
      len += lengths[data.charAt(i) & 0xFF];
    }
    return (int)((len + 7) >> 3);
  }
}
