/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.handler.ssl;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class Java8SslUtilsTest {

  @Test
  public void allowsNonDnsSniNamesInNonStrictMode() {
    // The ':' is not a legal character for DNS names
    Java8SslUtils.getSniHostNames(Arrays.asList("foo.com:1234"), false);
  }

  @Test
  public void doesNotAllowNonDnsSniNamesInStrictMode() {
    // The ':' is not a legal character for DNS names

    assertThrows(IllegalArgumentException.class, new Executable() {
      @Override
      public void execute() throws Throwable {
        Java8SslUtils.getSniHostNames(Arrays.asList("foo.com:1234"), true);
      }
    });
  }

//  @Test
//  public void defaultModeIsStrict() {
//    // The ':' is not a legal character for DNS names
//
//    assertThrows(IllegalArgumentException.class, new Executable() {
//      @Override
//      public void execute() throws Throwable {
//        Java8SslUtils.getSniHostNames(Arrays.asList("foo.com:1234"));
//      }
//    });
//  }
}
