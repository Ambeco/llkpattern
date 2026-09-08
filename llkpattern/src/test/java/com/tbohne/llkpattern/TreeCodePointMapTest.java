package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@link CodePointMapTestBase}'s shared cases, run against {@link TreeCodePointMap}. */
@RunWith(JUnit4.class)
public class TreeCodePointMapTest extends CodePointMapTestBase {
  @Override
  <V> MutableCodePointMap<V> create() {
    return new TreeCodePointMap<>();
  }
}
