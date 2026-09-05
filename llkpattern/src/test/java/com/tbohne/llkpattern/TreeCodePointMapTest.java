package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import com.tbohne.llkpattern.CodePointMap.ConflictingMappingException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TreeCodePointMapTest {

  @Test
  public void isEmpty_newMap_isTrue() {
    assertThat(new TreeCodePointMap<String>().isEmpty(), is(true));
  }

  @Test
  public void isEmpty_afterPut_isFalse() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', "letter");
    assertThat(map.isEmpty(), is(false));
  }

  @Test
  public void get_singleCodePoint_returnsPutValue() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', "letter");
    assertThat(map.get('a'), equalTo("letter"));
  }

  @Test
  public void get_codePointOutsideAnyRange_returnsNull() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', "letter");
    assertThat(map.get('b'), nullValue());
  }

  @Test
  public void get_range_maxIsExclusive() {
    // put(min, max, ...) is [min, max): the code point at `max` should NOT be included.
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', 'd', "letter"); // covers 'a', 'b', 'c'
    assertThat(map.get('a'), equalTo("letter"));
    assertThat(map.get('c'), equalTo("letter"));
    assertThat(map.get('d'), nullValue());
  }

  @Test
  public void put_overwritesPriorMappingInRange() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', "first");
    map.put('a', "second");
    assertThat(map.get('a'), equalTo("second"));
  }

  @Test
  public void containsKeys_fullyCoveredRange_isTrue() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', 'z' + 1, "letter");
    assertThat(map.containsKeys('a', 'z' + 1), is(true));
  }

  @Test
  public void containsKeys_partiallyCoveredRange_isFalse() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', 'c', "letter"); // only covers 'a', 'b'
    assertThat(map.containsKeys('a', 'd'), is(false));
  }

  @Test
  public void containsKeys_disjointPutsCoveringQueryRange_isTrue() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', 'b', "x");
    map.put('b', 'c', "y");
    assertThat(map.containsKeys('a', 'c'), is(true));
  }

  @Test
  public void remove_removesOnlyRequestedRange() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', 'd', "letter"); // 'a','b','c'
    map.remove('b', 'c'); // remove just 'b'
    assertThat(map.get('a'), equalTo("letter"));
    assertThat(map.get('b'), nullValue());
    assertThat(map.get('c'), equalTo("letter"));
  }

  @Test
  public void putAll_copiesOtherMapsEntries() {
    TreeCodePointMap<String> a = new TreeCodePointMap<>();
    a.put('a', "a-value");
    TreeCodePointMap<String> b = new TreeCodePointMap<>();
    b.put('b', "b-value");

    a.putAll(b);

    assertThat(a.get('a'), equalTo("a-value"));
    assertThat(a.get('b'), equalTo("b-value"));
  }

  @Test
  public void union_disjointMaps_hasBothEntries() {
    TreeCodePointMap<String> a = new TreeCodePointMap<>();
    a.put('a', "a-value");
    TreeCodePointMap<String> b = new TreeCodePointMap<>();
    b.put('b', "b-value");

    CodePointMap<String> result = a.union(b);

    assertThat(result.get('a'), equalTo("a-value"));
    assertThat(result.get('b'), equalTo("b-value"));
  }

  @Test
  public void union_overlappingMaps_otherWins() {
    TreeCodePointMap<String> a = new TreeCodePointMap<>();
    a.put('a', "a-value");
    TreeCodePointMap<String> b = new TreeCodePointMap<>();
    b.put('a', "b-value");

    assertThat(a.union(b).get('a'), equalTo("b-value"));
  }

  @Test
  public void difference_removesOverlappingCodePoints() {
    TreeCodePointMap<String> a = new TreeCodePointMap<>();
    a.put('a', 'c', "letter"); // 'a', 'b'
    TreeCodePointMap<String> b = new TreeCodePointMap<>();
    b.put('a', "letter");

    CodePointMap<String> result = a.difference(b);

    assertThat(result.get('a'), nullValue());
    assertThat(result.get('b'), equalTo("letter"));
  }

  @Test
  public void intersection_byRange_keepsOnlyOverlap() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', 'z' + 1, "letter");

    CodePointMap<String> result = map.intersection('x', 'z' + 1);

    assertThat(result.get('a'), nullValue());
    assertThat(result.get('x'), equalTo("letter"));
    assertThat(result.get('y'), equalTo("letter"));
  }

  @Test
  public void intersectionRejectingConflicts_agreeingMaps_keepsOverlap() {
    TreeCodePointMap<String> a = new TreeCodePointMap<>();
    a.put('a', 'z' + 1, "letter");
    TreeCodePointMap<String> b = new TreeCodePointMap<>();
    b.put('m', "letter");

    CodePointMap<String> result = a.intersectionRejectingConflicts(b);

    assertThat(result.get('m'), equalTo("letter"));
    assertThat(result.get('a'), nullValue());
  }

  @Test
  public void intersectionRejectingConflicts_disagreeingMaps_throws() {
    // This is the case QuantifiedUnion's ambiguity detection depends on: two branches (here,
    // modeled as two CodePointMaps) that both claim the same code point are a compile error.
    TreeCodePointMap<String> branch1 = new TreeCodePointMap<>();
    branch1.put('a', "branch1");
    TreeCodePointMap<String> branch2 = new TreeCodePointMap<>();
    branch2.put('a', "branch2");

    assertThrows(
        ConflictingMappingException.class, () -> branch1.intersectionRejectingConflicts(branch2));
  }

  @Test
  public void compute_absentCodePoint_appliesFunctionWithNullOldValue() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.compute('a', (cp, old) -> old == null ? "computed" : "unexpected");
    assertThat(map.get('a'), equalTo("computed"));
  }

  @Test
  public void compute_presentCodePoint_returnsOldValueAndAppliesFunction() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', "before");
    String returned = map.compute('a', (cp, old) -> old + "-after");
    assertThat(returned, equalTo("before"));
    assertThat(map.get('a'), equalTo("before-after"));
  }

  @Test
  public void computeIfAbsent_presentCodePoint_leavesValueUnchanged() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', "existing");
    map.computeIfAbsent('a', cp -> "should not be used");
    assertThat(map.get('a'), equalTo("existing"));
  }

  @Test
  public void complement_excludesMappedCodePointsOnly() {
    TreeCodePointMap<String> map = new TreeCodePointMap<>();
    map.put('a', "letter");

    CodePointMap<String> complement = map.complement("other");

    assertThat(complement.get('a'), nullValue());
    assertThat(complement.get('b'), equalTo("other"));
  }

  @Test
  public void equals_sameEntries_areEqual() {
    TreeCodePointMap<String> a = new TreeCodePointMap<>();
    a.put('a', 'c', "letter");
    TreeCodePointMap<String> b = new TreeCodePointMap<>();
    b.put('a', 'c', "letter");
    assertThat(a, equalTo(b));
    assertThat(a.hashCode(), equalTo(b.hashCode()));
  }

  @Test
  public void copyConstructor_isIndependentOfSource() {
    TreeCodePointMap<String> original = new TreeCodePointMap<>();
    original.put('a', "letter");

    TreeCodePointMap<String> copy = new TreeCodePointMap<>(original);
    original.put('b', "second");

    assertThat(copy.get('a'), equalTo("letter"));
    assertThat(copy.get('b'), nullValue());
  }
}
