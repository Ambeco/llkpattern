package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@link CodePointMapTestBase}'s shared cases, run against {@link ArrayCodePointMap}. */
@RunWith(JUnit4.class)
public class ArrayCodePointMapTest extends CodePointMapTestBase {
  @Override
  <V> MutableCodePointMap<V> create() {
    return new ArrayCodePointMap<>();
  }

  @Test
  public void put_adjacentRangesSameValue_coalesceIntoOneEntry() {
    // Unlike TreeCodePointMap (Guava's TreeRangeMap does not auto-coalesce, see its class doc),
    // ArrayCodePointMap normalizes adjacent equal-value entries into one on every mutation.
    MutableCodePointMap<String> map = create();
    map.put('a', 'b', "x");
    map.put('b', 'c', "x");
    assertThat(map.entrySet().size(), is(1));
  }

  @Test
  public void put_veryLongRange_splitsIntoCapacitySizedEntriesButStaysOneLogicalValue() {
    MutableCodePointMap<String> map = create();
    // Longer than a single entry's 2048-code-point capacity: unavoidably split (see class doc),
    // but every entry should still carry the same value and the split should be minimal
    // (ceil(10_000 / 2048) == 5 entries), not one per original put() chunk boundary or worse.
    map.put(0, 10_000, "v");
    assertThat(map.entrySet().size(), is(5));
    for (java.util.Map.Entry<CodePointMap.Range, String> e : map.entrySet()) {
      assertThat(e.getValue(), is("v"));
    }
    assertThat(map.get(0), is("v"));
    assertThat(map.get(9_999), is("v"));
    assertThat(map.get(10_000), org.hamcrest.CoreMatchers.nullValue());
    assertThat(map.containsKeys(0, 10_000), is(true));
  }
}
