package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.tbohne.llkpattern.CodePointSet.MutableCodePointSet;
import org.junit.Test;

/** Behavioral tests for {@link ArrayCodePointSet}. */
public class ArrayCodePointSetTest {

  private MutableCodePointSet create() {
    return new ArrayCodePointSet();
  }

  @Test
  public void isEmpty_newSet_isTrue() {
    assertThat(create().isEmpty(), is(true));
  }

  @Test
  public void isEmpty_afterAdd_isFalse() {
    MutableCodePointSet set = create();
    set.add('a');
    assertThat(set.isEmpty(), is(false));
  }

  @Test
  public void contains_singleCodePoint_returnsTrue() {
    MutableCodePointSet set = create();
    set.add('a');
    assertThat(set.contains('a'), is(true));
  }

  @Test
  public void contains_codePointOutsideAnyRange_returnsFalse() {
    MutableCodePointSet set = create();
    set.add('a');
    assertThat(set.contains('b'), is(false));
  }

  @Test
  public void contains_range_maxIsExclusive() {
    MutableCodePointSet set = create();
    set.add('a', 'd'); // covers 'a', 'b', 'c'
    assertThat(set.contains('a'), is(true));
    assertThat(set.contains('c'), is(true));
    assertThat(set.contains('d'), is(false));
  }

  @Test
  public void add_overlappingRanges_merge() {
    MutableCodePointSet set = create();
    set.add('a', 'c');
    set.add('b', 'e');
    assertThat(set.contains('a'), is(true));
    assertThat(set.contains('d'), is(true));
    assertThat(set.rangeSet(), equalTo(java.util.Set.of(new CodePointSet.Range('a', 'e'))));
  }

  @Test
  public void containsAll_fullyCoveredRange_isTrue() {
    MutableCodePointSet set = create();
    set.add('a', 'z' + 1);
    assertThat(set.containsAll('a', 'z' + 1), is(true));
  }

  @Test
  public void containsAll_partiallyCoveredRange_isFalse() {
    MutableCodePointSet set = create();
    set.add('a', 'c'); // only covers 'a', 'b'
    assertThat(set.containsAll('a', 'd'), is(false));
  }

  @Test
  public void containsAll_disjointAddsCoveringQueryRange_isTrue() {
    MutableCodePointSet set = create();
    set.add('a', 'b');
    set.add('b', 'c');
    assertThat(set.containsAll('a', 'c'), is(true));
  }

  @Test
  public void remove_removesOnlyRequestedRange() {
    MutableCodePointSet set = create();
    set.add('a', 'd'); // 'a','b','c'
    set.remove('b', 'c'); // remove just 'b'
    assertThat(set.contains('a'), is(true));
    assertThat(set.contains('b'), is(false));
    assertThat(set.contains('c'), is(true));
  }

  @Test
  public void addAll_copiesOtherSetsEntries() {
    MutableCodePointSet a = create();
    a.add('a');
    MutableCodePointSet b = create();
    b.add('b');

    a.addAll(b);

    assertThat(a.contains('a'), is(true));
    assertThat(a.contains('b'), is(true));
  }

  @Test
  public void union_disjointSets_hasBothMembers() {
    MutableCodePointSet a = create();
    a.add('a');
    MutableCodePointSet b = create();
    b.add('b');

    CodePointSet result = a.union(b);

    assertThat(result.contains('a'), is(true));
    assertThat(result.contains('b'), is(true));
  }

  @Test
  public void difference_removesOverlappingCodePoints() {
    MutableCodePointSet a = create();
    a.add('a', 'c'); // 'a', 'b'
    MutableCodePointSet b = create();
    b.add('a');

    CodePointSet result = a.difference(b);

    assertThat(result.contains('a'), is(false));
    assertThat(result.contains('b'), is(true));
  }

  @Test
  public void intersection_byRange_keepsOnlyOverlap() {
    MutableCodePointSet set = create();
    set.add('a', 'z' + 1);

    CodePointSet result = set.intersection('x', 'z' + 1);

    assertThat(result.contains('a'), is(false));
    assertThat(result.contains('x'), is(true));
    assertThat(result.contains('y'), is(true));
  }

  @Test
  public void intersects_overlappingRanges_isTrue() {
    MutableCodePointSet a = create();
    a.add('a', 'm'); // [a, m)
    MutableCodePointSet b = create();
    b.add('g', 'z' + 1); // [g, {) -- overlaps a in [g, m)

    assertThat(a.intersects(b), is(true));
    assertThat(b.intersects(a), is(true)); // symmetric
  }

  @Test
  public void intersects_disjointRanges_isFalse() {
    MutableCodePointSet a = create();
    a.add('a', 'c'); // [a, c)
    MutableCodePointSet b = create();
    b.add('x', 'z' + 1); // [x, {)

    assertThat(a.intersects(b), is(false));
    assertThat(b.intersects(a), is(false));
  }

  @Test
  public void intersects_touchingRanges_isFalse() {
    // [a, c) and [c, e) share no code point -- 'c' belongs only to the second range.
    MutableCodePointSet a = create();
    a.add('a', 'c');
    MutableCodePointSet b = create();
    b.add('c', 'e');

    assertThat(a.intersects(b), is(false));
  }

  @Test
  public void intersects_invertedSet_treatsGapsAsMembers() {
    // Inverted `a` excludes ['b', 'y'), i.e. its real members are everything else -- including
    // 'a' and 'z', both of which `b` also claims.
    MutableCodePointSet a = create();
    a.add('b', 'y');
    a.invert();
    MutableCodePointSet b = create();
    b.add('a');
    b.add('z');

    assertThat(a.intersects(b), is(true));
  }

  @Test
  public void intersects_invertedSetFullyCoveringOther_isFalse() {
    // Inverted `a` excludes ['a', 'z' + 1) entirely, so it has no members in that whole range --
    // whatever `b` claims within it can't be a real intersection.
    MutableCodePointSet a = create();
    a.add('a', 'z' + 1);
    a.invert();
    MutableCodePointSet b = create();
    b.add('m');

    assertThat(a.intersects(b), is(false));
  }

  @Test
  public void complement_excludesMemberCodePointsOnly() {
    MutableCodePointSet set = create();
    set.add('a');

    CodePointSet complement = set.complement();

    assertThat(complement.contains('a'), is(false));
    assertThat(complement.contains('b'), is(true));
  }

  @Test
  public void complement_isIndependentOfSource() {
    MutableCodePointSet set = create();
    set.add('a');

    CodePointSet complement = set.complement(); // "everything except 'a'" -- includes 'b'
    set.add('b'); // if complement aliased set's backing array, this would corrupt it

    assertThat(complement.contains('a'), is(false));
    assertThat(complement.contains('b'), is(true)); // unaffected by the later mutation
  }

  @Test
  public void doubleComplement_roundTrips() {
    MutableCodePointSet set = create();
    set.add('a', 'd');

    CodePointSet doubled = set.complement().complement();

    assertThat(doubled.contains('a'), is(true));
    assertThat(doubled.contains('c'), is(true));
    assertThat(doubled.contains('d'), is(false));
    assertThat(doubled, equalTo(set));
  }

  @Test
  public void invert_mutatesInPlace() {
    MutableCodePointSet set = create();
    set.add('a');
    set.invert();
    assertThat(set.contains('a'), is(false));
    assertThat(set.contains('b'), is(true));
  }

  @Test
  public void equals_sameEntries_areEqual() {
    MutableCodePointSet a = create();
    a.add('a', 'c');
    MutableCodePointSet b = create();
    b.add('a', 'c');
    assertThat(a, equalTo(b));
    assertThat(a.hashCode(), equalTo(b.hashCode()));
  }

  @Test
  public void copyConstructor_isIndependentOfSource() {
    MutableCodePointSet original = create();
    original.add('a');

    MutableCodePointSet copy = new ArrayCodePointSet(original);
    original.add('b');

    assertThat(copy.contains('a'), is(true));
    assertThat(copy.contains('b'), is(false));
  }

  @Test
  public void add_rangeLongerThanElevenBitCount_isStillFullyCovered() {
    // Regression coverage for the 2048-code-point-per-entry packing limit: a single add() spanning
    // more than that must still behave as one range.
    MutableCodePointSet set = create();
    set.add(0x4E00, 0x4E00 + 5000); // arbitrary >2048-long range, well within the BMP
    assertThat(set.contains(0x4E00), is(true));
    assertThat(set.contains(0x4E00 + 2047), is(true));
    assertThat(set.contains(0x4E00 + 2048), is(true));
    assertThat(set.contains(0x4E00 + 4999), is(true));
    assertThat(set.contains(0x4E00 + 5000), is(false));
    assertThat(set.containsAll(0x4E00, 0x4E00 + 5000), is(true));
  }

  @Test
  public void add_touchingNeighborAcrossElevenBitBoundary_coalescesAndRechunks() {
    // Regression coverage for addRange's merge-window absorbing a *touching* (not overlapping)
    // neighbor and re-chunking across the 2048-entry boundary, now that there's no separate
    // tryCoalesceAt pass -- add() has to fold the neighbor in and re-split it itself.
    MutableCodePointSet set = create();
    set.add(0, 1500); // one entry, [0, 1500)
    set.add(1500, 3500); // touches the first entry's max exactly -- must merge into one run,
    // [0, 3500), which no longer fits in a single 2048-wide entry.
    assertThat(set.contains(0), is(true));
    assertThat(set.contains(1499), is(true));
    assertThat(set.contains(1500), is(true));
    assertThat(set.contains(3499), is(true));
    assertThat(set.contains(3500), is(false));
    assertThat(set.containsAll(0, 3500), is(true));
  }

  @Test
  public void add_refillingRemovedGap_shrinksChunkCountBelowWindowSize() {
    // Regression coverage for addRange's delta < 0 path: remove() can leave one logical run
    // represented as MORE (smaller) entries than the 2048-cap requires -- e.g. cutting a gap out
    // of the middle of a single entry splits it into two small entries via insertSingle -- so a
    // later add() re-filling that gap needs FEWER chunks than the window it's replacing.
    MutableCodePointSet set = create();
    set.add(0, 2000); // one entry, [0, 2000)
    set.remove(900, 1100); // splits it into two entries: [0, 900) and [1100, 2000)
    assertThat(set.contains(950), is(false));

    set.add(900, 1100); // re-fills the gap; merged span [0, 2000) fits back in a single chunk, so
    // this window (2 existing entries) shrinks to 1 -- exercising addRange's arraycopy-left path.
    assertThat(set.containsAll(0, 2000), is(true));
    assertThat(set.contains(2000), is(false));

    MutableCodePointSet expected = create();
    expected.add(0, 2000);
    assertThat(set, equalTo(expected));
  }

  @Test
  public void contains_codePointNearMaxCodePoint_isSupported() {
    MutableCodePointSet set = create();
    set.add(CodePointSet.MAX_CODE_POINT);
    assertThat(set.contains(CodePointSet.MAX_CODE_POINT), is(true));
    assertThat(set.contains(CodePointSet.MAX_CODE_POINT - 1), is(false));
  }

  @Test
  public void first_matchingRangePresent_returnsTrue() {
    MutableCodePointSet set = create();
    set.add('a');
    assertThat(set.first((min, max) -> min == 'a'), is(true));
  }

  @Test
  public void first_noMatchingRange_returnsFalse() {
    MutableCodePointSet set = create();
    set.add('a');
    assertThat(set.first((min, max) -> min == 'b'), is(false));
  }

  @Test
  public void first_stopsAtFirstMatch_doesNotVisitLaterRanges() {
    MutableCodePointSet set = create();
    set.add('a', 'b' + 1);
    set.add('d', 'e' + 1); // a disjoint second range, visited second
    int[] visitCount = {0};

    boolean found = set.first((min, max) -> {
      visitCount[0]++;
      return true; // matches on the very first range visited
    });

    assertThat(found, is(true));
    assertThat(visitCount[0], equalTo(1));
  }

  @Test
  public void first_onInvertedSet_seesGapRangesToo() {
    MutableCodePointSet set = create();
    set.add('a');
    CodePointSet complement = set.complement(); // inverted: 'a' is now the only NON-member

    assertThat(complement.first((min, max) -> min == 0), is(true)); // the [0, 'a') gap
    assertThat(complement.first((min, max) -> min == 'a' && max == 'a' + 1), is(false));
  }
}
