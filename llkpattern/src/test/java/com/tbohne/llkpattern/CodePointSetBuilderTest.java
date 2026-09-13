package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.tbohne.llkpattern.CodePointSet.MutableCodePointSet;
import org.junit.Test;

public class CodePointSetBuilderTest {

  @Test
  public void build_singleRange_isMember() {
    CodePointSetBuilder builder = new CodePointSetBuilder();
    builder.add('a', 'd');
    CodePointSet set = builder.build();
    assertThat(set.contains('a'), is(true));
    assertThat(set.contains('c'), is(true));
    assertThat(set.contains('d'), is(false));
  }

  @Test
  public void build_outOfOrderRanges_areSortedAndCoalesced() {
    CodePointSetBuilder builder = new CodePointSetBuilder();
    builder.add('c');
    builder.add('a');
    builder.add('b');
    CodePointSet set = builder.build();
    assertThat(set.contains('a'), is(true));
    assertThat(set.contains('b'), is(true));
    assertThat(set.contains('c'), is(true));
    assertThat(set.rangeSet(), is(java.util.Set.of(new CodePointMap.Range('a', 'c' + 1))));
  }

  @Test
  public void build_overlappingRangesFromDifferentSources_merge() {
    CodePointSetBuilder builder = new CodePointSetBuilder();
    builder.add('a', 'c'); // 'a', 'b'
    builder.add('b', 'd'); // 'b', 'c' -- overlaps, never a conflict for a plain set
    CodePointSet set = builder.build();
    assertThat(set.contains('a'), is(true));
    assertThat(set.contains('b'), is(true));
    assertThat(set.contains('c'), is(true));
    assertThat(set.contains('d'), is(false));
  }

  @Test
  public void addAll_pushesSourceSetsRanges() {
    CodePointSetBuilder builder = new CodePointSetBuilder();
    MutableCodePointSet source = new ArrayCodePointSet();
    source.add('x', 'z' + 1);
    builder.addAll(source);
    CodePointSet set = builder.build();
    assertThat(set.contains('x'), is(true));
    assertThat(set.contains('z'), is(true));
  }
}
