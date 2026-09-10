package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import java.util.function.Supplier;
import org.junit.Test;

/**
 * Behavioral test cases shared by every {@link MutableCodePointMap} implementation. Subclasses
 * just supply a factory; see {@link TreeCodePointMapTest} and {@link ArrayCodePointMapTest}.
 */
public abstract class CodePointMapTestBase {

  abstract <V> MutableCodePointMap<V> create();

  private <V> MutableCodePointMap<V> create(MutableCodePointMap<V> other) {
    MutableCodePointMap<V> result = create();
    result.putAll(other);
    return result;
  }

  @Test
  public void isEmpty_newMap_isTrue() {
    assertThat(this.<String>create().isEmpty(), is(true));
  }

  @Test
  public void isEmpty_afterPut_isFalse() {
    MutableCodePointMap<String> map = create();
    map.put('a', "letter");
    assertThat(map.isEmpty(), is(false));
  }

  @Test
  public void get_singleCodePoint_returnsPutValue() {
    MutableCodePointMap<String> map = create();
    map.put('a', "letter");
    assertThat(map.get('a'), equalTo("letter"));
  }

  @Test
  public void get_codePointOutsideAnyRange_returnsNull() {
    MutableCodePointMap<String> map = create();
    map.put('a', "letter");
    assertThat(map.get('b'), nullValue());
  }

  @Test
  public void get_range_maxIsExclusive() {
    MutableCodePointMap<String> map = create();
    map.put('a', 'd', "letter"); // covers 'a', 'b', 'c'
    assertThat(map.get('a'), equalTo("letter"));
    assertThat(map.get('c'), equalTo("letter"));
    assertThat(map.get('d'), nullValue());
  }

  @Test
  public void put_overwritesPriorMappingInRange() {
    MutableCodePointMap<String> map = create();
    map.put('a', "first");
    map.put('a', "second");
    assertThat(map.get('a'), equalTo("second"));
  }

  @Test
  public void containsKeys_fullyCoveredRange_isTrue() {
    MutableCodePointMap<String> map = create();
    map.put('a', 'z' + 1, "letter");
    assertThat(map.containsKeys('a', 'z' + 1), is(true));
  }

  @Test
  public void containsKeys_partiallyCoveredRange_isFalse() {
    MutableCodePointMap<String> map = create();
    map.put('a', 'c', "letter"); // only covers 'a', 'b'
    assertThat(map.containsKeys('a', 'd'), is(false));
  }

  @Test
  public void containsKeys_disjointPutsCoveringQueryRange_isTrue() {
    MutableCodePointMap<String> map = create();
    map.put('a', 'b', "x");
    map.put('b', 'c', "y");
    assertThat(map.containsKeys('a', 'c'), is(true));
  }

  @Test
  public void remove_removesOnlyRequestedRange() {
    MutableCodePointMap<String> map = create();
    map.put('a', 'd', "letter"); // 'a','b','c'
    map.remove('b', 'c'); // remove just 'b'
    assertThat(map.get('a'), equalTo("letter"));
    assertThat(map.get('b'), nullValue());
    assertThat(map.get('c'), equalTo("letter"));
  }

  @Test
  public void putAll_copiesOtherMapsEntries() {
    MutableCodePointMap<String> a = create();
    a.put('a', "a-value");
    MutableCodePointMap<String> b = create();
    b.put('b', "b-value");

    a.putAll(b);

    assertThat(a.get('a'), equalTo("a-value"));
    assertThat(a.get('b'), equalTo("b-value"));
  }

  @Test
  public void union_disjointMaps_hasBothEntries() {
    MutableCodePointMap<String> a = create();
    a.put('a', "a-value");
    MutableCodePointMap<String> b = create();
    b.put('b', "b-value");

    CodePointMap<String> result = a.union(b);

    assertThat(result.get('a'), equalTo("a-value"));
    assertThat(result.get('b'), equalTo("b-value"));
  }

  @Test
  public void union_overlappingMaps_otherWins() {
    MutableCodePointMap<String> a = create();
    a.put('a', "a-value");
    MutableCodePointMap<String> b = create();
    b.put('a', "b-value");

    assertThat(a.union(b).get('a'), equalTo("b-value"));
  }

  @Test
  public void difference_removesOverlappingCodePoints() {
    MutableCodePointMap<String> a = create();
    a.put('a', 'c', "letter"); // 'a', 'b'
    MutableCodePointMap<String> b = create();
    b.put('a', "letter");

    CodePointMap<String> result = a.difference(b);

    assertThat(result.get('a'), nullValue());
    assertThat(result.get('b'), equalTo("letter"));
  }

  @Test
  public void intersection_byRange_keepsOnlyOverlap() {
    MutableCodePointMap<String> map = create();
    map.put('a', 'z' + 1, "letter");

    CodePointMap<String> result = map.intersection('x', 'z' + 1);

    assertThat(result.get('a'), nullValue());
    assertThat(result.get('x'), equalTo("letter"));
    assertThat(result.get('y'), equalTo("letter"));
  }

  @Test
  public void compute_absentCodePoint_appliesFunctionWithNullOldValue() {
    MutableCodePointMap<String> map = create();
    map.compute('a', (cp, old) -> old == null ? "computed" : "unexpected");
    assertThat(map.get('a'), equalTo("computed"));
  }

  @Test
  public void compute_presentCodePoint_returnsOldValueAndAppliesFunction() {
    MutableCodePointMap<String> map = create();
    map.put('a', "before");
    String returned = map.compute('a', (cp, old) -> old + "-after");
    assertThat(returned, equalTo("before"));
    assertThat(map.get('a'), equalTo("before-after"));
  }

  @Test
  public void computeIfAbsent_presentCodePoint_leavesValueUnchanged() {
    MutableCodePointMap<String> map = create();
    map.put('a', "existing");
    map.computeIfAbsent('a', cp -> "should not be used");
    assertThat(map.get('a'), equalTo("existing"));
  }

  @Test
  public void complement_excludesMappedCodePointsOnly() {
    MutableCodePointMap<String> map = create();
    map.put('a', "letter");

    CodePointMap<String> complement = map.complement("other");

    assertThat(complement.get('a'), nullValue());
    assertThat(complement.get('b'), equalTo("other"));
  }

  @Test
  public void equals_sameEntries_areEqual() {
    MutableCodePointMap<String> a = create();
    a.put('a', 'c', "letter");
    MutableCodePointMap<String> b = create();
    b.put('a', 'c', "letter");
    assertThat(a, equalTo(b));
    assertThat(a.hashCode(), equalTo(b.hashCode()));
  }

  @Test
  public void copyConstructor_isIndependentOfSource() {
    MutableCodePointMap<String> original = create();
    original.put('a', "letter");

    MutableCodePointMap<String> copy = create(original);
    original.put('b', "second");

    assertThat(copy.get('a'), equalTo("letter"));
    assertThat(copy.get('b'), nullValue());
  }

  @Test
  public void put_rangeLongerThanElevenBitCount_isStillFullyCovered() {
    // Regression coverage for ArrayCodePointMap's 2048-code-point-per-entry packing limit: a
    // single put() spanning more than that must still behave as one logical mapping.
    MutableCodePointMap<String> map = create();
    map.put(0x4E00, 0x4E00 + 5000, "han"); // arbitrary >2048-long range, well within the BMP
    assertThat(map.get(0x4E00), equalTo("han"));
    assertThat(map.get(0x4E00 + 2047), equalTo("han"));
    assertThat(map.get(0x4E00 + 2048), equalTo("han"));
    assertThat(map.get(0x4E00 + 4999), equalTo("han"));
    assertThat(map.get(0x4E00 + 5000), nullValue());
    assertThat(map.containsKeys(0x4E00, 0x4E00 + 5000), is(true));
  }

  @Test
  public void get_codePointNearMaxCodePoint_isSupported() {
    MutableCodePointMap<String> map = create();
    map.put(CodePointMap.MAX_CODE_POINT, CodePointMap.MAX_CODE_POINT + 1, "top");
    assertThat(map.get(CodePointMap.MAX_CODE_POINT), equalTo("top"));
    assertThat(map.get(CodePointMap.MAX_CODE_POINT - 1), nullValue());
  }

  @Test
  public void first_matchingRangePresent_returnsTrue() {
    MutableCodePointMap<String> map = create();
    map.put('a', "letter");
    assertThat(map.first((min, max, value) -> value.equals("letter")), is(true));
  }

  @Test
  public void first_noMatchingRange_returnsFalse() {
    MutableCodePointMap<String> map = create();
    map.put('a', "letter");
    assertThat(map.first((min, max, value) -> value.equals("digit")), is(false));
  }

  @Test
  public void first_stopsAtFirstMatch_doesNotVisitLaterRanges() {
    MutableCodePointMap<String> map = create();
    map.put('a', "a-value"); // ascending order by min, so this range is visited first
    map.put('b', "b-value");
    int[] visitCount = {0};

    boolean found = map.first((min, max, value) -> {
      visitCount[0]++;
      return true; // matches on the very first range visited
    });

    assertThat(found, is(true));
    assertThat(visitCount[0], equalTo(1));
  }

  @Test
  public void first_withElseValue_seesGapRangesToo() {
    MutableCodePointMap<String> map = create();
    map.put('a', "letter");
    CodePointMap<String> complement = map.complement("other"); // else-valued: fills every gap

    assertThat(complement.first((min, max, value) -> value.equals("other")), is(true));
    assertThat(complement.first((min, max, value) -> value.equals("nonexistent")), is(false));
  }
}
