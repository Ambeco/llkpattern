package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.tbohne.llkpattern.CodePointSet.MutableCodePointSet;
import org.junit.Test;

public class CodePointSetBuilderTest {

  @Test
  public void build_singleRange_isMember() {
    CodePointSetBuilder builder = CodePointSetBuilder.create();
    builder.add('a', 'd');
    CodePointSet set = builder.build();
    assertThat(set.contains('a'), is(true));
    assertThat(set.contains('c'), is(true));
    assertThat(set.contains('d'), is(false));
  }

  @Test
  public void build_outOfOrderRanges_areSortedAndCoalesced() {
    CodePointSetBuilder builder = CodePointSetBuilder.create();
    builder.add('c');
    builder.add('a');
    builder.add('b');
    CodePointSet set = builder.build();
    assertThat(set.contains('a'), is(true));
    assertThat(set.contains('b'), is(true));
    assertThat(set.contains('c'), is(true));
    assertThat(set.rangeSet(), is(java.util.Set.of(new CodePointSet.Range('a', 'c' + 1))));
  }

  @Test
  public void build_overlappingRangesFromDifferentSources_merge() {
    CodePointSetBuilder builder = CodePointSetBuilder.create();
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
    CodePointSetBuilder builder = CodePointSetBuilder.create();
    MutableCodePointSet source = new ArrayCodePointSet();
    source.add('x', 'z' + 1);
    builder.addAll(source);
    CodePointSet set = builder.build();
    assertThat(set.contains('x'), is(true));
    assertThat(set.contains('z'), is(true));
  }

  @Test
  public void mergeRun_negatedLiteralsOnly_isComplementOfLiterals() {
    CodePointSetBuilder builder = CodePointSetBuilder.create();
    builder.add('a', 'd');
    CodePointSet set = CodePointSetBuilder.mergeRun(builder, null, true);
    assertThat(set.contains('a'), is(false));
    assertThat(set.contains('e'), is(true));
  }

  @Test
  public void mergeRun_negatedWithEmptyLiteralsAndRunUnion_returnsComplementCopy_leavesRunUnionUntouched() {
    MutableCodePointSet runUnion = new ArrayCodePointSet();
    runUnion.add('a', 'd');
    CodePointSetBuilder emptyBuilder = CodePointSetBuilder.create();
    CodePointSet set = CodePointSetBuilder.mergeRun(emptyBuilder, runUnion, true);
    assertThat(set.contains('a'), is(false));
    assertThat(set.contains('e'), is(true));
    // The fast-path source (e.g. a shared NamedCharClass constant) must not be mutated in place --
    // negate on this path must copy, since another caller may hold the same instance.
    assertThat(runUnion.contains('a'), is(true));
    assertThat(runUnion.contains('e'), is(false));
  }

  @Test
  public void mergeRun_negatedWithLiteralsAndRunUnion_isComplementOfUnion() {
    CodePointSetBuilder builder = CodePointSetBuilder.create();
    builder.add('a', 'c'); // 'a', 'b'
    MutableCodePointSet runUnion = new ArrayCodePointSet();
    runUnion.add('x', 'z' + 1);
    CodePointSet set = CodePointSetBuilder.mergeRun(builder, runUnion, true);
    assertThat(set.contains('a'), is(false));
    assertThat(set.contains('x'), is(false));
    assertThat(set.contains('m'), is(true));
  }
}
