package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointSet.Range;
import com.tbohne.llkpattern.CodePointSet.MutableCodePointSet;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@link CodePointSet} implementation specialized to the Unicode code point domain, backed by a
 * single flat array of packed {@code (min << 11) | count} keys, kept sorted by {@code min} (so
 * every lookup is a binary/linear-search hybrid over {@code keys} -- see {@link #floorIndex}), each
 * entry spanning at most 2048 code points ({@code count}'s 11 bits). {@link #invert} is what makes
 * membership testing cheap without any parallel "value" array: since a set has only two states at
 * any code point ("in" or "out"), {@code keys} always means exactly the same thing (a run of code
 * points explicitly recorded), and {@code invert} just says whether that recorded run IS the set
 * ({@code invert == false}) or is EXCLUDED from it ({@code invert == true}, i.e. the set is
 * everything else). {@link #complement()} is therefore a flip of one boolean (plus a cheap array
 * copy, to keep this class's instances independently mutable) rather than a real rebuild.
 *
 * <p>Homogeneous entries (there's no "value" to disagree on, unlike a generic map) also simplify
 * {@link #add}/{@link #appendSorted}: two overlapping or touching entries always merge
 * unconditionally, with no value-equality check needed anywhere.
 */
public class ArrayCodePointSet implements MutableCodePointSet {
  // count occupies the low 11 bits (max 2047, i.e. entries span at most 2048 code points).
  private static final int COUNT_BITS = 11;
  // Package-private (not private): CodePointSetBuilder's own Impl needs it for the same
  // chunk-count math when packing its own keys array.
  static final int MAX_COUNT = (1 << COUNT_BITS) - 1;

  private static final int INITIAL_CAPACITY = 1;

  // Package-private, not private: CodePointSetBuilder's own Impl subclasses this class directly
  // (rather than composing a separate builder object that hands a finished array off to a fresh
  // ArrayCodePointSet -- see that class's own doc for why) and needs to read/write these fields
  // itself, both while accumulating (unsorted, via its own overridden #add) and in its own #build
  // (sorting/coalescing this same array in place before handing `this` back as the finished set).
  //
  // Kept sorted by min (equivalently, by key, since min occupies the packed key's high bits --
  // every comparison here extracts min via floorIndex/keyMin rather than comparing packed keys as
  // raw ints, purely for readability, not correctness) ONCE an instance is a real, finished
  // CodePointSet -- every method below this point assumes that invariant already holds. `size` is
  // the logical entry count; `keys.length` is capacity, which can run ahead of `size` -- see
  // ensureCapacity.
  int[] keys;
  int size;

  // Whether `keys`' recorded runs ARE this set (false, the common/default case) or are EXCLUDED
  // from it (true, i.e. this set is everything else) -- see the class doc.
  boolean invert;

  public ArrayCodePointSet() {
    this(INITIAL_CAPACITY);
  }

  /**
   * Same as the no-arg constructor, but starting {@code keys} at {@code initialCapacity} instead
   * of {@link #INITIAL_CAPACITY} -- for a subclass (namely {@link CodePointSetBuilderImpl}) whose
   * typical real accumulation is known to be bigger than this class's own single-character common
   * case, so it's worth avoiding the first one or two {@code Arrays.copyOf} regrows rather than
   * inheriting a capacity tuned for a different usage pattern.
   */
  ArrayCodePointSet(int initialCapacity) {
    keys = new int[initialCapacity];
    size = 0;
  }

  public ArrayCodePointSet(CodePointSet other) {
    this();
    addAll(other);
  }

  /**
   * The complement of {@code source}: a plain array copy plus a flipped {@link #invert} bit. A
   * static factory, not another constructor overload -- {@code ArrayCodePointSet(ArrayCodePointSet)}
   * would be MORE specific than the public copy constructor {@link #ArrayCodePointSet(CodePointSet)}
   * for any same-class argument, so every {@code new ArrayCodePointSet(this)} call made from
   * *within* this class (e.g. {@link #union}) would silently resolve to the complement constructor
   * instead of the plain copy -- exactly the bug this factory avoids by not being a constructor
   * overload at all.
   */
  private static ArrayCodePointSet complementOf(ArrayCodePointSet source) {
    ArrayCodePointSet result = new ArrayCodePointSet();
    result.keys = Arrays.copyOf(source.keys, source.size);
    result.size = source.size;
    result.invert = !source.invert;
    return result;
  }

  // Package-private (not private): CodePointSetBuilder's own Impl (a direct subclass) packs its
  // own accumulated entries with this, both while appending and while re-chunking merged runs in
  // its own #build.
  static int packKey(int min, int count) {
    return (min << COUNT_BITS) | count;
  }

  // Package-private (not private): CodePointSetBuilder's own Impl sorts/merges its own packed
  // entries by their real (min, max) extent in its own #build, without duplicating this
  // bit-unpacking logic.
  static int keyMin(int key) {
    return key >>> COUNT_BITS;
  }

  private static int keyCount(int key) {
    return key & MAX_COUNT;
  }

  static int keyMax(int key) { // exclusive
    return keyMin(key) + keyCount(key) + 1;
  }

  /** Index of the last entry whose min is {@code <= codePoint}, or {@code -1} if none. */
  private static final int LINEAR_SEARCH_THRESHOLD = 65;

  // Static, with `keys`/`size` passed as parameters, rather than an instance method reading
  // `this.keys`/`this.size` directly -- an experiment (per the project owner) checking whether a
  // pure helper on this class's own hot path (this is the `contains`/`containsAll` leaf profiling
  // has repeatedly shown as the hottest single method during matching) benefits from losing the
  // implicit receiver. JMH showed no measurable difference either way (see notes.md's dated
  // entry) -- kept in this shape anyway since it's no worse and makes the "pure function of its
  // arguments" nature explicit, but this is NOT expected to matter for any other private helper
  // in this codebase and shouldn't be treated as a new default style to chase elsewhere.
  private static int floorIndex(int[] keys, int size, int codePoint) {
    int lo = 0;
    int hi = size - 1;
    while (lo + LINEAR_SEARCH_THRESHOLD <= hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (keyMin(keys[mid]) <= codePoint) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    for (int i = hi; i >= lo; i--) {
      if (keyMin(keys[i]) <= codePoint) {
        return i;
      }
    }
    return -1;
  }

  private int windowStart(int min) {
    int idx = floorIndex(keys, size, min);
    if (idx < 0 || keyMax(keys[idx]) <= min) {
      idx++;
    }
    return idx;
  }

  private int windowEnd(int start, int max) {
    int end = start;
    while (end < size && keyMin(keys[end]) < max) {
      end++;
    }
    return end;
  }

  @Override
  public boolean intersects(CodePointSet other) {
    return other.first(this::overlapsRange);
  }

  /**
   * Whether this set contains at least one code point in {@code [min, max)} -- the allocation-free
   * half of {@link #intersects}: no new {@code CodePointSet} materialized, unlike {@code
   * !intersection(min, max).isEmpty()}.
   */
  private boolean overlapsRange(int min, int max) {
    if (!invert) {
      int idx = windowStart(min);
      return idx < size && keyMin(keys[idx]) < max;
    }
    // Inverted: members are the GAPS between entries (see the class doc) -- walk the window
    // looking for either a gap before an entry or a trailing gap after the last one, same
    // cursor-walk shape as forEachRange/intersection's own invert branches.
    int start = windowStart(min);
    int end = windowEnd(start, max);
    int cursor = min;
    for (int i = start; i < end; i++) {
      int entryMin = Math.max(min, keyMin(keys[i]));
      if (cursor < entryMin) {
        return true;
      }
      cursor = Math.max(cursor, Math.min(max, keyMax(keys[i])));
    }
    return cursor < max;
  }

  /**
   * Like {@link #windowStart}, but for {@link #add}/{@link #appendSorted}'s merge semantics: a
   * merely-touching entry (its {@code max} exactly equal to {@code min}) counts too, since it's
   * about to be fused into one run with the new range rather than left as a separate one.
   */
  private int addWindowStart(int min) {
    int idx = floorIndex(keys, size, min);
    if (idx < 0 || keyMax(keys[idx]) < min) {
      idx++;
    }
    return idx;
  }

  /** The {@link #addWindowStart} counterpart of {@link #windowEnd} -- touching entries included. */
  private int addWindowEnd(int start, int max) {
    int end = start;
    while (end < size && keyMin(keys[end]) <= max) {
      end++;
    }
    return end;
  }

  @Override
  public boolean isEmpty() {
    if (!invert) {
      return size == 0;
    }
    // Inverted: this set is empty only if its excluded entries cover the ENTIRE domain with no
    // gaps -- an empty exclusion list means "everything is a member", i.e. NOT empty.
    int cursor = 0;
    for (int i = 0; i < size; i++) {
      if (keyMin(keys[i]) != cursor) {
        return false; // a gap here is a real member -- not empty
      }
      cursor = keyMax(keys[i]);
    }
    return cursor > MAX_CODE_POINT;
  }

  @Override
  public boolean contains(int codePoint) {
    int idx = floorIndex(keys, size, codePoint);
    boolean explicit = idx >= 0 && codePoint < keyMax(keys[idx]);
    return explicit != invert;
  }

  @Override
  public boolean containsAll(int min, int max) {
    if (invert) {
      // Every code point in [min, max) is "in" this set iff none of them is one of the explicitly
      // recorded (excluded) code points -- i.e. no entry overlaps the window at all.
      int start = windowStart(min);
      return start >= windowEnd(start, max);
    }
    int cp = min;
    while (cp < max) {
      int idx = floorIndex(keys, size, cp);
      if (idx >= 0 && cp < keyMax(keys[idx])) {
        cp = keyMax(keys[idx]);
      } else {
        return false;
      }
    }
    return true;
  }

  @Override
  public void forEachRange(RangeConsumer action) {
    if (!invert) {
      for (int i = 0; i < size; i++) {
        action.accept(keyMin(keys[i]), keyMax(keys[i]));
      }
      return;
    }
    // Inverted: the recorded entries are the GAPS -- members are everything between them.
    int cursor = 0;
    for (int i = 0; i < size; i++) {
      int entryMin = keyMin(keys[i]);
      int entryMax = keyMax(keys[i]);
      if (cursor < entryMin) {
        action.accept(cursor, entryMin);
      }
      cursor = entryMax;
    }
    if (cursor <= MAX_CODE_POINT) {
      action.accept(cursor, MAX_CODE_POINT + 1);
    }
  }

  @Override
  public boolean first(RangePredicate predicate) {
    if (!invert) {
      for (int i = 0; i < size; i++) {
        if (predicate.test(keyMin(keys[i]), keyMax(keys[i]))) {
          return true;
        }
      }
      return false;
    }
    int cursor = 0;
    for (int i = 0; i < size; i++) {
      int entryMin = keyMin(keys[i]);
      int entryMax = keyMax(keys[i]);
      if (cursor < entryMin && predicate.test(cursor, entryMin)) {
        return true;
      }
      cursor = entryMax;
    }
    return cursor <= MAX_CODE_POINT && predicate.test(cursor, MAX_CODE_POINT + 1);
  }

  @Override
  public CodePointSet intersection(int min, int max) {
    ArrayCodePointSet result = new ArrayCodePointSet();
    if (!invert) {
      int start = windowStart(min);
      int end = windowEnd(start, max);
      result.ensureCapacity(end - start);
      for (int i = start; i < end; i++) {
        int lo = Math.max(min, keyMin(keys[i]));
        int hi = Math.min(max, keyMax(keys[i]));
        if (lo < hi) {
          result.appendSorted(lo, hi);
        }
      }
      return result;
    }
    // Inverted: the gaps between entries (within the window) are the real members.
    int cursor = min;
    for (int i = windowStart(min); i < size && cursor < max; i++) {
      int entryMin = keyMin(keys[i]);
      if (entryMin >= max) {
        break;
      }
      int entryMax = keyMax(keys[i]);
      if (cursor < entryMin) {
        result.appendSorted(cursor, Math.min(entryMin, max));
      }
      cursor = entryMax;
    }
    if (cursor < max) {
      result.appendSorted(cursor, max);
    }
    return result;
  }

  /**
   * Overrides the interface default with an allocation-light two-pointer sweep over both sets'
   * raw {@code keys} arrays -- one temporary {@link ArrayCodePointSet} total (the result), versus
   * the default's one-per-range-of-{@code this} plus a binary-search {@link #add} per emitted
   * sub-range. Falls back to the default for a non-{@link ArrayCodePointSet} {@code other} (e.g. a
   * lazy {@code UnionCodePointSet}) -- every real caller on the parse-time hot path
   * ({@code PatternParser}'s {@code &&} handling) already has both operands as concrete {@code
   * ArrayCodePointSet}s by the time they reach here (see {@code CodePointSetBuilder#mergeRun}'s own
   * doc), so this is the path that actually matters.
   *
   * <p>Handles {@link #invert} by De Morgan's laws, since {@code keys} always holds the same raw
   * ranges regardless of the flag: two normal sets sweep-intersect directly; two inverted sets'
   * intersection is the (raw) union of their keys, inverted (<code>&#x2201;A &cap; &#x2201;B =
   * &#x2201;(A &cup; B)</code>); one of each is the normal side's raw ranges minus the inverted
   * side's raw ranges (<code>A &cap; &#x2201;B = A - B</code>).
   */
  @Override
  public CodePointSet intersection(CodePointSet other) {
    if (!(other instanceof ArrayCodePointSet)) {
      return MutableCodePointSet.super.intersection(other);
    }
    ArrayCodePointSet o = (ArrayCodePointSet) other;
    if (!invert && !o.invert) {
      return sweepIntersect(this, o);
    }
    if (invert && o.invert) {
      ArrayCodePointSet result = sweepUnion(this, o);
      result.invert = true;
      return result;
    }
    return invert ? sweepDifference(o, this) : sweepDifference(this, o);
  }

  /** Two normal (non-inverted) sets' raw ranges, intersected via a linear merge-scan. */
  private static ArrayCodePointSet sweepIntersect(ArrayCodePointSet a, ArrayCodePointSet b) {
    ArrayCodePointSet result = new ArrayCodePointSet();
    result.ensureCapacity(Math.min(a.size, b.size));
    int i = 0, j = 0;
    while (i < a.size && j < b.size) {
      int aMin = keyMin(a.keys[i]), aMax = keyMax(a.keys[i]);
      int bMin = keyMin(b.keys[j]), bMax = keyMax(b.keys[j]);
      int lo = Math.max(aMin, bMin), hi = Math.min(aMax, bMax);
      if (lo < hi) {
        result.appendSorted(lo, hi);
      }
      if (aMax < bMax) {
        i++;
      } else {
        j++;
      }
    }
    return result;
  }

  /** {@code a}'s raw ranges minus {@code b}'s, both normal (non-inverted), via a linear scan. */
  private static ArrayCodePointSet sweepDifference(ArrayCodePointSet a, ArrayCodePointSet b) {
    ArrayCodePointSet result = new ArrayCodePointSet();
    result.ensureCapacity(a.size + b.size);
    int bi = 0;
    for (int ai = 0; ai < a.size; ai++) {
      int cursor = keyMin(a.keys[ai]);
      int aMax = keyMax(a.keys[ai]);
      while (cursor < aMax) {
        while (bi < b.size && keyMax(b.keys[bi]) <= cursor) {
          bi++;
        }
        if (bi >= b.size || keyMin(b.keys[bi]) >= aMax) {
          result.appendSorted(cursor, aMax);
          cursor = aMax;
        } else {
          int bMin = keyMin(b.keys[bi]), bMax = keyMax(b.keys[bi]);
          if (cursor < bMin) {
            result.appendSorted(cursor, bMin);
          }
          cursor = Math.max(cursor, bMax);
        }
      }
    }
    return result;
  }

  /**
   * {@code a}'s and {@code b}'s raw ranges, unioned via a linear merge (not {@link #addAll}).
   * Coalesces into a running {@code (curMin, curMax)} accumulator before ever calling {@link
   * #appendSorted} -- NOT simply "append whichever source's next range has the smaller min":
   * once a run merged from both sources spans more than 2048 code points, it's split across
   * multiple physical {@code keys} chunks, and a later source range can have a min that's
   * ascending relative to every range appended so far yet still falls behind the true covered
   * frontier (inside an earlier physical chunk of that same logical run, not just the last one) --
   * which would violate {@code appendSorted}'s "only touches/overlaps the LAST entry" precondition.
   * Coalescing first guarantees every {@code appendSorted} call gets a truly maximal run, so this
   * can never happen. (Found by this method's own differential fuzz test against the interface
   * default -- see {@code ArrayCodePointSetTest}.)
   */
  private static ArrayCodePointSet sweepUnion(ArrayCodePointSet a, ArrayCodePointSet b) {
    ArrayCodePointSet result = new ArrayCodePointSet();
    result.ensureCapacity(a.size + b.size);
    int i = 0, j = 0;
    boolean haveCurrent = false;
    int curMin = 0, curMax = 0;
    while (i < a.size || j < b.size) {
      int min, max;
      if (j >= b.size || (i < a.size && keyMin(a.keys[i]) <= keyMin(b.keys[j]))) {
        min = keyMin(a.keys[i]);
        max = keyMax(a.keys[i]);
        i++;
      } else {
        min = keyMin(b.keys[j]);
        max = keyMax(b.keys[j]);
        j++;
      }
      if (!haveCurrent) {
        curMin = min;
        curMax = max;
        haveCurrent = true;
      } else if (min <= curMax) {
        curMax = Math.max(curMax, max);
      } else {
        result.appendSorted(curMin, curMax);
        curMin = min;
        curMax = max;
      }
    }
    if (haveCurrent) {
      result.appendSorted(curMin, curMax);
    }
    return result;
  }

  @Override
  public CodePointSet complement() {
    return complementOf(this);
  }

  @Override
  public void invert() {
    invert = !invert;
  }

  /** Grows the backing array (by 1.5x, or to {@code minCapacity} if that's bigger) if needed. */
  @Override
  public void ensureCapacity(int minCapacity) {
    if (keys.length < minCapacity) {
      int newCapacity = Math.max(minCapacity, keys.length + (keys.length >> 1) + 1);
      keys = Arrays.copyOf(keys, newCapacity);
    }
  }

  @Override
  public void add(int min, int max) {
    int start = addWindowStart(min);
    int end = addWindowEnd(start, max);
    addRange(start, end, min, max);
  }

  /** Bulk-appends a single range; see {@link MutableCodePointSet#appendSorted}'s contract. */
  @Override
  public void appendSorted(int min, int max) {
    if (min >= max) {
      return; // empty range -- guard needed since addRange treats an empty [start,end) touching
              // the last entry as "shrink it away", not "no-op".
    }
    // Sorted-append input can only ever touch/overlap the LAST existing entry (everything else is
    // strictly before it), so the merge window is found by a plain check instead of the binary
    // search add() needs.
    int start = (size > 0 && keyMax(keys[size - 1]) >= min) ? size - 1 : size;
    addRange(start, size, min, max);
  }

  /**
   * Replaces the entries at {@code [start, end)} -- which, on entry, touch or overlap {@code [min,
   * max)} and nothing else does -- with however many packed chunks are needed to cover their union,
   * shifting the tail as needed. No value to disagree on (unlike a generic map's {@code put}) -- any
   * existing entry in the window just merges into one wider run with the new range, so this never
   * needs a separate coalesce pass, and writes the chunks directly into {@code keys} with no
   * intermediate array.
   */
  private void addRange(int start, int end, int min, int max) {
    int lo = (start < end) ? Math.min(min, keyMin(keys[start])) : min;
    int hi = (start < end) ? Math.max(max, keyMax(keys[end - 1])) : max;
    int chunkCount = (hi - lo + MAX_COUNT) / (MAX_COUNT + 1); // ceil((hi - lo) / 2048)
    int delta = chunkCount - (end - start);
    if (delta != 0) {
      // Growing needs room for the tail's new position BEFORE it's shifted; shrinking never needs
      // more room than it already has, so ensureCapacity would be a guaranteed (harmless, but
      // pointless) no-op -- skipped rather than called unconditionally.
      if (delta > 0) {
        ensureCapacity(size + delta);
      }
      System.arraycopy(keys, end, keys, end + delta, size - end);
      size += delta;
    }
    int w = start;
    for (int chunkMin = lo; chunkMin < hi; chunkMin += MAX_COUNT + 1) {
      int chunkMax = Math.min(hi, chunkMin + MAX_COUNT + 1);
      keys[w] = packKey(chunkMin, chunkMax - chunkMin - 1);
      w++;
    }
  }

  @Override
  public void addAll(CodePointSet other) {
    if (other instanceof ArrayCodePointSet) {
      ArrayCodePointSet o = (ArrayCodePointSet) other;
      if (o.size == 0 && !o.invert) {
        return;
      }
      if (size == 0 && !invert && !o.invert) {
        // Fast path: this set has nothing of its own yet, so addAll degenerates to a plain array
        // copy.
        keys = Arrays.copyOf(o.keys, o.size);
        size = o.size;
        return;
      }
    }
    // General case: a plain range-at-a-time add() per source range, not a sorted-sweep merge (no
    // "other wins on overlap" semantics to preserve here -- a union just needs every source range
    // folded in, and add() already merges anything it touches/overlaps) -- correctness-first given
    // most callers build these sets from scratch via appendSorted, not addAll/union. This path IS
    // reached on a real, if smaller, hot path though -- PatternConstruct#unionLastCharSet/lastCharSet
    // fold a multi-alternative loop/union body's branches together this way -- so it's not the "cold
    // path only" case it once was; see notes.md's 2026-09-25 entry if it's worth a sweep merge too.
    other.forEachRange(this::add);
  }

  @Override
  public CodePointSet union(CodePointSet other) {
    ArrayCodePointSet result = new ArrayCodePointSet(this);
    result.addAll(other);
    return result;
  }

  @Override
  public CodePointSet difference(CodePointSet other) {
    ArrayCodePointSet result = new ArrayCodePointSet(this);
    result.removeAll(other);
    return result;
  }

  @Override
  public void remove(int min, int max) {
    if (size == 0 || min >= max) {
      return;
    }
    int start = windowStart(min);
    int end = windowEnd(start, max);
    if (start >= end) {
      return;
    }
    if (end - start == 1) {
      int key = keys[start];
      int entryMin = keyMin(key);
      int entryMax = keyMax(key);
      boolean keepLeft = entryMin < min;
      boolean keepRight = entryMax > max;
      if (keepLeft && keepRight) {
        keys[start] = packKey(entryMin, min - entryMin - 1);
        insertSingle(start + 1, packKey(max, entryMax - max - 1));
      } else if (keepLeft) {
        keys[start] = packKey(entryMin, min - entryMin - 1);
      } else if (keepRight) {
        keys[start] = packKey(max, entryMax - max - 1);
      } else {
        deleteRange(start, start + 1);
      }
      return;
    }
    if (keyMin(keys[start]) < min) {
      int entryMin = keyMin(keys[start]);
      keys[start] = packKey(entryMin, min - entryMin - 1);
      start++;
    }
    if (keyMax(keys[end - 1]) > max) {
      int entryMax = keyMax(keys[end - 1]);
      keys[end - 1] = packKey(max, entryMax - max - 1);
      end--;
    }
    deleteRange(start, end);
  }

  private void deleteRange(int from, int to) {
    if (from >= to) {
      return;
    }
    System.arraycopy(keys, to, keys, from, size - to);
    size -= (to - from);
  }

  private void insertSingle(int idx, int key) {
    ensureCapacity(size + 1);
    System.arraycopy(keys, idx, keys, idx + 1, size - idx);
    keys[idx] = key;
    size++;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder(invert ? "![" : "[");
    boolean[] needsComma = {false};
    forEachRange((min, max) -> {
      if (needsComma[0]) {
        sb.append(", ");
      }
      needsComma[0] = true;
      sb.append(new Range(min, max));
    });
    return sb.append(']').toString();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (other instanceof ArrayCodePointSet) {
      ArrayCodePointSet o = (ArrayCodePointSet) other;
      if (size != o.size || invert != o.invert) {
        return false;
      }
      for (int i = 0; i < size; i++) {
        if (keys[i] != o.keys[i]) {
          return false;
        }
      }
      return true;
    }
    if (!(other instanceof CodePointSet)) {
      return false;
    }
    Set<Range> mine = new LinkedHashSet<>();
    forEachRange((min, max) -> mine.add(new Range(min, max)));
    Set<Range> theirs = new LinkedHashSet<>();
    ((CodePointSet) other).forEachRange((min, max) -> theirs.add(new Range(min, max)));
    return mine.equals(theirs);
  }

  @Override
  public int hashCode() {
    int[] hash = {0};
    forEachRange((min, max) -> hash[0] += new Range(min, max).hashCode());
    return hash[0];
  }

  /**
   * A {@link CodePointSetBuilder} that IS an {@link ArrayCodePointSet} -- see that interface's own
   * doc for why. Overrides {@link #add}, the one method whose semantics genuinely differ while
   * accumulating (unsorted append here, vs. {@link ArrayCodePointSet}'s own sorted-insert-with-shift);
   * {@link #addAll}/{@link #invert} are overridden only to add the build-once guard, then delegate
   * straight to the inherited implementation -- {@link ArrayCodePointSet#addAll}'s own fast path (a
   * plain array copy when this is still empty) and general path (one {@link #add} per source range)
   * both keep working unmodified, the latter correctly reaching THIS class's overridden {@link #add}
   * via ordinary virtual dispatch. Lives here (nested in {@link ArrayCodePointSet}, package-private,
   * not in {@code CodePointSetBuilder.java}) since the two are tightly intertwined implementation
   * details of each other -- this class reaches into {@code ArrayCodePointSet}'s own package-private
   * {@code keys}/{@code size}/{@code packKey}/{@code keyMin}/{@code keyMax}/{@code MAX_COUNT}
   * directly, and {@link ArrayCodePointSet}'s own {@code (int initialCapacity)} constructor exists
   * purely for this class's benefit.
   */
  static final class CodePointSetBuilderImpl extends ArrayCodePointSet implements CodePointSetBuilder {
    // Bigger than ArrayCodePointSet's own INITIAL_CAPACITY (1, tuned for that class's typical
    // single-character common case): this builder's real caller (a bracket expression's literal
    // members) typically accumulates a small handful of ranges, not one, so starting bigger avoids
    // the first one or two Arrays.copyOf regrows -- see notes.md for the measurement history behind
    // this number (4 was the value the original, non-inheriting design used; this restores it after
    // an inheritance-based rewrite temporarily lost it).
    private static final int BUILDER_INITIAL_CAPACITY = 4;

    private boolean built = false;

    CodePointSetBuilderImpl() {
      super(BUILDER_INITIAL_CAPACITY);
    }

    // Explicit override needed: CodePointSetBuilder#add(int) and MutableCodePointSet#add(int) (via
    // ArrayCodePointSet) both provide unrelated default implementations of the same signature --
    // javac can't pick one on its own. Both just forward to #add(int,int) anyway.
    @Override
    public void add(int codePoint) {
      add(codePoint, codePoint + 1);
    }

    @Override
    public void add(int min, int max) {
      checkNotBuilt();
      // Packed immediately in ArrayCodePointSet's own compact key format -- not deferred to
      // #build -- chunking any range wider than MAX_COUNT into multiple entries here, the same way
      // ArrayCodePointSet#addRange does for an incremental insert.
      for (int chunkMin = min; chunkMin < max; chunkMin += MAX_COUNT + 1) {
        int chunkMax = Math.min(max, chunkMin + MAX_COUNT + 1);
        appendKey(packKey(chunkMin, chunkMax - chunkMin - 1));
      }
    }

    private void appendKey(int key) {
      if (size == keys.length) {
        keys = Arrays.copyOf(keys, keys.length + (keys.length >> 1) + 1);
      }
      keys[size] = key;
      size++;
    }

    @Override
    public void addAll(CodePointSet source) {
      checkNotBuilt();
      super.addAll(source);
    }

    @Override
    public void invert() {
      checkNotBuilt();
      super.invert();
    }

    @Override
    public CodePointSet build() {
      checkNotBuilt();
      built = true;
      sortInPlaceByMin();
      // Single forward pass: for each maximal run of touching/overlapping entries (by real
      // extent, not by their individual pre-chunked boundaries), re-chunk the run's own full span
      // into however many packed entries it actually needs, writing them back into the SAME
      // array. The write cursor can never run ahead of the read cursor: a run built from N input
      // entries can never need MORE than N output chunks (each input entry already covers up to
      // MAX_COUNT+1 code points, so covering the same combined span can't take more chunks than
      // that), so this is safe to compact in place.
      int outSize = 0;
      int i = 0;
      while (i < size) {
        int runMin = keyMin(keys[i]);
        int runMax = keyMax(keys[i]);
        i++;
        while (i < size && keyMin(keys[i]) <= runMax) {
          runMax = Math.max(runMax, keyMax(keys[i]));
          i++;
        }
        for (int chunkMin = runMin; chunkMin < runMax; chunkMin += MAX_COUNT + 1) {
          int chunkMax = Math.min(runMax, chunkMin + MAX_COUNT + 1);
          keys[outSize] = packKey(chunkMin, chunkMax - chunkMin - 1);
          outSize++;
        }
      }
      size = outSize;
      return this;
    }

    private void checkNotBuilt() {
      if (built) {
        throw new IllegalStateException(
            "CodePointSetBuilder already built -- create a new builder per CodePointSet instead "
                + "of reusing one (see class doc)");
      }
    }

    /**
     * Insertion sort of {@code keys[0..size)} by each entry's real (unpacked) min -- worth it over
     * {@code Arrays.sort} despite its worse worst-case complexity, since every real call site's
     * input is small and near-sorted already, where insertion sort's low constant factor wins.
     * Compares extracted min, not the raw packed {@code int}s directly -- {@code min << 11} can
     * overflow the sign bit as low as code point 0x100000, so raw numeric order isn't reliable
     * (same reasoning as {@code ArrayCodePointSet#floorIndex}'s own comparisons, which never
     * compare raw packed keys either).
     */
    private void sortInPlaceByMin() {
      for (int i = 1; i < size; i++) {
        int key = keys[i];
        int min = keyMin(key);
        int j = i - 1;
        while (j >= 0 && keyMin(keys[j]) > min) {
          keys[j + 1] = keys[j];
          j--;
        }
        keys[j + 1] = key;
      }
    }
  }
}
