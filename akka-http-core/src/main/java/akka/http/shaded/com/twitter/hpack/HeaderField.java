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

import static akka.http.shaded.com.twitter.hpack.HpackUtil.requireNonNull;

class HeaderField implements Comparable<HeaderField> {

  // Section 4.1. Calculating Table Size
  // The additional 32 octets account for an estimated
  // overhead associated with the structure.
  static final int HEADER_ENTRY_OVERHEAD = 32;

  static int sizeOf(String name, String value) {
    return name.length() + value.length() + HEADER_ENTRY_OVERHEAD;
  }

  final String name;
  final String value;
  Object parsedValue = null;

  // This constructor can only be used if name and value are ISO-8859-1 encoded.
  HeaderField(String name, String value) {
    this.name = requireNonNull(name);
    this.value = requireNonNull(value);
  }
  HeaderField(String name, String value, Object parsedValue) {
    this.name = requireNonNull(name);
    this.value = requireNonNull(value);
    this.parsedValue = parsedValue;
  }

  int size() {
    return name.length() + value.length() + HEADER_ENTRY_OVERHEAD;
  }

  @Override
  public int compareTo(HeaderField anotherHeaderField) {
    int ret = compareTo(name, anotherHeaderField.name);
    if (ret == 0) {
      ret = compareTo(value, anotherHeaderField.value);
    }
    return ret;
  }

  private int compareTo(String s1, String s2) {
    return s1.compareTo(s2);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof HeaderField)) {
      return false;
    }
    HeaderField other = (HeaderField) obj;
    return name.equals(other.name) && value.equals(other.value);
  }

  @Override
  public String toString() {
    return name + ": " + value;
  }
}
