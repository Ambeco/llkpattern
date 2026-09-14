package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.tbohne.llkpattern.CodePointSet.Range;
import com.tbohne.llkpattern.CodePointSet.MutableCodePointSet;
import java.util.Set;
import org.junit.Test;

public class UnionCodePointSetTest {

  private static MutableCodePointSet setOf(int... codePoints) {
    MutableCodePointSet set = new ArrayCodePointSet();
    for (int cp : codePoints) {
      set.add(cp);
    }
    return set;
  }

  @Test
  public void contains_memberOfEitherDelegate_isTrue() {
    CodePointSet union = new UnionCodePointSet(setOf('a'), setOf('b'));
    assertThat(union.contains('a'), is(true));
    assertThat(union.contains('b'), is(true));
    assertThat(union.contains('c'), is(false));
  }

  @Test
  public void isEmpty_bothDelegatesEmpty_isTrue() {
    CodePointSet union = new UnionCodePointSet(new ArrayCodePointSet(), new ArrayCodePointSet());
    assertThat(union.isEmpty(), is(true));
  }

  @Test
  public void isEmpty_oneDelegateNonEmpty_isFalse() {
    CodePointSet union = new UnionCodePointSet(setOf('a'), new ArrayCodePointSet());
    assertThat(union.isEmpty(), is(false));
  }

  @Test
  public void forEachRange_disjointDelegates_yieldsBothRangesInOrder() {
    MutableCodePointSet a = new ArrayCodePointSet();
    a.add('d', 'f' + 1); // d,e,f
    MutableCodePointSet b = new ArrayCodePointSet();
    b.add('a', 'b' + 1); // a,b
    CodePointSet union = new UnionCodePointSet(a, b); // a starts after b -- exercises ordering

    assertThat(union.rangeSet(), equalTo(Set.of(new Range('a', 'c'), new Range('d', 'g'))));
  }

  @Test
  public void forEachRange_overlappingDelegates_coalesce() {
    MutableCodePointSet a = new ArrayCodePointSet();
    a.add('a', 'e'); // a,b,c,d
    MutableCodePointSet b = new ArrayCodePointSet();
    b.add('c', 'g'); // c,d,e,f
    CodePointSet union = new UnionCodePointSet(a, b);

    assertThat(union.rangeSet(), equalTo(Set.of(new Range('a', 'g'))));
  }

  @Test
  public void forEachRange_touchingDelegates_coalesce() {
    MutableCodePointSet a = new ArrayCodePointSet();
    a.add('a', 'c' + 1); // a,b,c
    MutableCodePointSet b = new ArrayCodePointSet();
    b.add('d', 'f' + 1); // d,e,f -- touches a's range exactly, no gap
    CodePointSet union = new UnionCodePointSet(a, b);

    assertThat(union.rangeSet(), equalTo(Set.of(new Range('a', 'g'))));
  }

  @Test
  public void forEachRange_interleavedRanges_mergeCorrectly() {
    // Regression coverage for a real bug: naively folding any b-range with min <= the current
    // accumulated max (rather than genuinely merging two sorted sequences) would wrongly bridge
    // non-adjacent ranges. 'a'/'b' delegate has [b,c] and [j,k]; the other has [f,g] -- none of
    // these touch or overlap, so all three must survive as separate ranges, with the true gaps
    // (c-f, g-j) NOT reported as members.
    MutableCodePointSet x = new ArrayCodePointSet();
    x.add('b', 'c' + 1);
    x.add('j', 'k' + 1);
    MutableCodePointSet y = setOf('f');
    CodePointSet union = new UnionCodePointSet(x, y);

    assertThat(union.contains('d'), is(false));
    assertThat(union.contains('g'), is(false));
    assertThat(union.rangeSet(), equalTo(Set.of(new Range('b', 'c' + 1), new Range('f', 'f' + 1), new Range('j', 'k' + 1))));
  }

  @Test
  public void containsAll_fullyCoveredAcrossBothDelegates_isTrue() {
    MutableCodePointSet a = new ArrayCodePointSet();
    a.add('a', 'c' + 1); // a,b,c
    MutableCodePointSet b = new ArrayCodePointSet();
    b.add('d', 'f' + 1); // d,e,f
    CodePointSet union = new UnionCodePointSet(a, b);

    assertThat(union.containsAll('a', 'g'), is(true));
  }

  @Test
  public void containsAll_gapBetweenDelegates_isFalse() {
    MutableCodePointSet a = setOf('a');
    MutableCodePointSet b = setOf('z');
    CodePointSet union = new UnionCodePointSet(a, b);

    assertThat(union.containsAll('a', 'z' + 1), is(false));
  }

  @Test
  public void complement_excludesMembersOfEitherDelegate() {
    CodePointSet union = new UnionCodePointSet(setOf('a'), setOf('b'));
    CodePointSet complement = union.complement();
    assertThat(complement.contains('a'), is(false));
    assertThat(complement.contains('b'), is(false));
    assertThat(complement.contains('c'), is(true));
  }

  @Test
  public void union_ofUnion_staysLazyAndCorrect() {
    CodePointSet inner = new UnionCodePointSet(setOf('a'), setOf('b'));
    CodePointSet outer = inner.union(setOf('c'));
    assertThat(outer.contains('a'), is(true));
    assertThat(outer.contains('b'), is(true));
    assertThat(outer.contains('c'), is(true));
    assertThat(outer.contains('d'), is(false));
  }

  @Test
  public void equals_sameContentAsPlainSet_areEqual() {
    CodePointSet union = new UnionCodePointSet(setOf('a'), setOf('b'));
    MutableCodePointSet plain = setOf('a', 'b');
    assertThat(union, equalTo(plain));
    assertThat(union.hashCode(), equalTo(plain.hashCode()));
  }
}
