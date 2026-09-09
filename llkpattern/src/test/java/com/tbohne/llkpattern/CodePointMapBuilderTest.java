package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import com.tbohne.llkpattern.CodePointMap.ConflictingMappingException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CodePointMapBuilderTest {

  @Test
  public void build_empty_isEmpty() {
    CodePointMapBuilder<String> builder = new CodePointMapBuilder<>();
    assertThat(builder.build().isEmpty(), is(true));
  }

  @Test
  public void build_outOfOrderAdds_sortsCorrectly() {
    CodePointMapBuilder<String> builder = new CodePointMapBuilder<>();
    builder.add('c', "c");
    builder.add('a', "a");
    builder.add('b', "b");
    CodePointMap<String> map = builder.build();
    assertThat(map.get((int) 'a'), is("a"));
    assertThat(map.get((int) 'b'), is("b"));
    assertThat(map.get((int) 'c'), is("c"));
  }

  @Test
  public void build_adjacentEqualValues_coalesced() {
    CodePointMapBuilder<String> builder = new CodePointMapBuilder<>();
    builder.add('a', 'b', "x"); // [a,b)
    builder.add('b', 'c', "x"); // [b,c)
    CodePointMap<String> map = builder.build();
    assertThat(map.entrySet().size(), is(1));
  }

  @Test
  public void build_overlappingEqualValues_merged() {
    CodePointMapBuilder<String> builder = new CodePointMapBuilder<>();
    builder.add('a', 'z', "x");
    builder.add('c', 'g', "x"); // fully contained, same value: not a conflict
    CodePointMap<String> map = builder.build();
    assertThat(map.containsKeys('a', 'z'), is(true));
    assertThat(map.entrySet().size(), is(1));
  }

  @Test
  public void build_overlappingDifferentValues_throwsGenericConflict() {
    CodePointMapBuilder<String> builder = new CodePointMapBuilder<>();
    builder.add('a', 'm', "branch1");
    builder.add('g', 'z', "branch2");
    assertThrows(ConflictingMappingException.class, builder::build);
  }

  @Test
  public void build_overlappingDifferentValues_invokesCustomHandler() {
    CodePointMapBuilder<String> builder = new CodePointMapBuilder<>();
    builder.add('a', 'm', "branch1");
    builder.add('g', 'z', "branch2");
    StringBuilder seen = new StringBuilder();
    RuntimeException marker = new RuntimeException("stop");
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                builder.build(
                    (range, v1, otherRange, v2) -> {
                      seen.append(v1).append("/").append(v2);
                      throw marker;
                    }));
    assertThat(thrown, sameInstance(marker));
    assertThat(seen.toString(), is("branch1/branch2"));
  }

  @Test
  public void build_setElseValue_appliesToResult() {
    CodePointMapBuilder<String> builder = new CodePointMapBuilder<>();
    builder.add('a', "a");
    builder.setElseValue("else");
    CodePointMap<String> map = builder.build();
    assertThat(map.get((int) 'a'), is("a"));
    assertThat(map.get((int) 'b'), is("else"));
  }

  @Test
  public void add_nullValue_throws() {
    CodePointMapBuilder<String> builder = new CodePointMapBuilder<>();
    assertThrows(NullPointerException.class, () -> builder.add('a', null));
  }

  @Test
  public void build_disjointRanges_noConflict() {
    CodePointMapBuilder<String> builder = new CodePointMapBuilder<>();
    for (int i = 0; i < 20; i++) {
      builder.add('a' + i, "v" + i);
    }
    CodePointMap<String> map = builder.build();
    assertThat(map.entrySet().size(), is(20));
  }

  @Test
  public void build_adjacentLongRangesSameValue_mergedAndChunkedCorrectly() {
    // A single logical range spanning more than one 2048-code-point packed-entry chunk (see
    // ArrayCodePointMap's class doc), built from two adjacent add() calls that must merge before
    // the chunking pass (now done by ArrayCodePointMap's package-private
    // sorted-arrays constructor, not appendSorted) ever runs.
    CodePointMapBuilder<String> builder = new CodePointMapBuilder<>();
    builder.add(0, 3000, "x");
    builder.add(3000, 5000, "x");
    CodePointMap<String> map = builder.build();
    assertThat(map.containsKeys(0, 5000), is(true));
    // ceil(5000 / 2048) = 3 physical chunks, all logically the same value.
    assertThat(map.entrySet().size(), is(3));
    for (java.util.Map.Entry<CodePointMap.Range, String> e : map.entrySet()) {
      assertThat(e.getValue(), is("x"));
    }
  }
}
