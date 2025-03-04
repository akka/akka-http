/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
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

public interface HeaderListener {

  /**
   * emitHeader is called by the decoder during header field emission.
   * The name and value byte arrays must not be modified.
   */
  public Object addHeader(String name, String value, Object parsed, boolean sensitive);
}
