/*
 * Copyright 2010 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.android.apps.mytracks.util;

import junit.framework.TestCase;

/**
 * A unit test for {@link ChartsExtendedEncoder}.
 * 
 * @author Bartlomiej Niechwiej
 */
public class ChartsExtendedEncoderTest extends TestCase {

  public void testGetEncodedValue_validArguments() {
    // Valid arguments.
    assertEquals("AK", ChartsExtendedEncoder.getEncodedValue(10));
    assertEquals("JO", ChartsExtendedEncoder.getEncodedValue(590));
    assertEquals("AA", ChartsExtendedEncoder.getEncodedValue(0));
    // 64^2 = 4096.
    assertEquals("..", ChartsExtendedEncoder.getEncodedValue(4095));
  }

  public void testGetEncodedValue_invalidArguments() {
    // Invalid arguments.
    assertEquals("__", ChartsExtendedEncoder.getEncodedValue(4096));
    assertEquals("__", ChartsExtendedEncoder.getEncodedValue(1234564096));
    assertEquals("__", ChartsExtendedEncoder.getEncodedValue(-10));
    assertEquals("__", ChartsExtendedEncoder.getEncodedValue(-12324435));
  }

  public void testGetSeparator() {
    assertEquals(",", ChartsExtendedEncoder.getSeparator());
  }
}
