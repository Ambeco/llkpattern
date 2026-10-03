package com.tbohne.llkpattern.impl.unicode;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.tbohne.llkpattern.impl.unicode.CodePointSet.MutableCodePointSet;
import org.junit.Test;

public class CodePointSetBuilderTest {

  @Test
  public void build_singleRange_isMember() {
    CodePointSetBuilder builder = CodePointSetBuilder.create();
    builder.append('a', 'd');
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
    builder.append('a', 'c'); // 'a', 'b'
    builder.append('b', 'd'); // 'b', 'c' -- overlaps, never a conflict for a plain set
    CodePointSet set = builder.build();
    assertThat(set.contains('a'), is(true));
    assertThat(set.contains('b'), is(true));
    assertThat(set.contains('c'), is(true));
    assertThat(set.contains('d'), is(false));
  }

  @Test
  public void appendAll_pushesSourceSetsRanges() {
    CodePointSetBuilder builder = CodePointSetBuilder.create();
    MutableCodePointSet source = new ArrayCodePointSet();
    source.insert('x', 'z' + 1);
    builder.appendAll(source);
    CodePointSet set = builder.build();
    assertThat(set.contains('x'), is(true));
    assertThat(set.contains('z'), is(true));
  }

  @Test
  public void mergeRun_negatedLiteralsOnly_isComplementOfLiterals() {
    CodePointSetBuilder builder = CodePointSetBuilder.create();
    builder.append('a', 'd');
    CodePointSet set = CodePointSetBuilder.mergeRun(builder, null, true);
    assertThat(set.contains('a'), is(false));
    assertThat(set.contains('e'), is(true));
  }

  @Test
  public void mergeRun_negatedWithEmptyLiteralsAndRunUnion_returnsComplementCopy_leavesRunUnionUntouched() {
    MutableCodePointSet runUnion = new ArrayCodePointSet();
    runUnion.insert('a', 'd');
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
    builder.append('a', 'c'); // 'a', 'b'
    MutableCodePointSet runUnion = new ArrayCodePointSet();
    runUnion.insert('x', 'z' + 1);
    CodePointSet set = CodePointSetBuilder.mergeRun(builder, runUnion, true);
    assertThat(set.contains('a'), is(false));
    assertThat(set.contains('x'), is(false));
    assertThat(set.contains('m'), is(true));
  }

  /** Random shapes -- one run, two runs (the linear-merge path), many runs -- vs a boolean-array oracle. */
  @Test
  public void build_randomRunShapes_matchOracle() {
    java.util.Random random = new java.util.Random(12345);
    for (int trial = 0; trial < 3000; trial++) {
      boolean[] oracle = new boolean[400];
      CodePointSetBuilder builder = CodePointSetBuilder.create();
      int runs = 1 + random.nextInt(4);
      for (int run = 0; run < runs; run++) {
        int cursor = random.nextInt(20);
        int count = random.nextInt(14);
        for (int n = 0; n < count && cursor < 380; n++) {
          int min = cursor + random.nextInt(6);
          int max = min + 1 + random.nextInt(5);
          builder.append(min, max);
          for (int cp = min; cp < max; cp++) {
            oracle[cp] = true;
          }
          cursor = max + random.nextInt(6);
        }
      }
      CodePointSet set = builder.build();
      for (int cp = 0; cp < oracle.length; cp++) {
        assertThat("trial " + trial + " cp " + cp, set.contains(cp), is(oracle[cp]));
      }
      int[] ranges = {0};
      set.forEachRange((min, max) -> ranges[0]++);
      int expected = 0;
      for (int cp = 0; cp < oracle.length; cp++) {
        if (oracle[cp] && (cp == 0 || !oracle[cp - 1])) {
          expected++;
        }
      }
      assertThat("coalesced range count, trial " + trial, ranges[0], is(expected));
    }
  }
}
