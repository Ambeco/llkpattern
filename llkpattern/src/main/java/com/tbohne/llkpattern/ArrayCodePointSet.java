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
public final class ArrayCodePointSet implements MutableCodePointSet {
  // count occupies the low 11 bits (max 2047, i.e. entries span at most 2048 code points).
  private static final int COUNT_BITS = 11;
  private static final int MAX_COUNT = (1 << COUNT_BITS) - 1;

  private static final int INITIAL_CAPACITY = 1;

  // Kept sorted by min (equivalently, by key, since min occupies the packed key's high bits --
  // every comparison here extracts min via floorIndex/keyMin rather than comparing packed keys as
  // raw ints, purely for readability, not correctness). `size` is the logical entry count;
  // `keys.length` is capacity, which can run ahead of `size` -- see ensureCapacity.
  private int[] keys;
  private int size;

  // Whether `keys`' recorded runs ARE this set (false, the common/default case) or are EXCLUDED
  // from it (true, i.e. this set is everything else) -- see the class doc.
  private boolean invert;

  public ArrayCodePointSet() {
    keys = new int[INITIAL_CAPACITY];
    size = 0;
  }

  public ArrayCodePointSet(CodePointSet other) {
    this();
    addAll(other);
  }

  /**
   * Builds a set directly from {@code count} already-sorted-by-min, pairwise-disjoint,
   * coalesced-where-possible ranges -- the one packing/chunking pass any {@code appendSorted} loop
   * would do, but into a single correctly-sized {@code keys} array computed up front, instead of
   * growing via {@link #ensureCapacity} as it goes. Package-private -- reached only via {@link
   * CodePointSetBuilder#build}, which has already done the sort/merge work this constructor's
   * preconditions assume.
   */
  ArrayCodePointSet(int[] sortedMins, int[] sortedMaxs, int count) {
    int chunkTotal = 0;
    for (int i = 0; i < count; i++) {
      chunkTotal += (sortedMaxs[i] - sortedMins[i] + MAX_COUNT) / (MAX_COUNT + 1); // ceil(/2048)
    }
    keys = new int[chunkTotal];
    size = 0;
    for (int i = 0; i < count; i++) {
      int min = sortedMins[i];
      int max = sortedMaxs[i];
      for (int chunkMin = min; chunkMin < max; chunkMin += MAX_COUNT + 1) {
        int chunkMax = Math.min(max, chunkMin + MAX_COUNT + 1);
        keys[size] = packKey(chunkMin, chunkMax - chunkMin - 1);
        size++;
      }
    }
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

  private static int packKey(int min, int count) {
    return (min << COUNT_BITS) | count;
  }

  private static int keyMin(int key) {
    return key >>> COUNT_BITS;
  }

  private static int keyCount(int key) {
    return key & MAX_COUNT;
  }

  private static int keyMax(int key) { // exclusive
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
    int start = windowStart(min);
    int end = windowEnd(start, max);
    // No value to disagree on (unlike a generic map's `put`) -- any existing entry this new range
    // touches or overlaps just merges into one wider run.
    int lo = (start < end) ? Math.min(min, keyMin(keys[start])) : min;
    int hi = (start < end) ? Math.max(max, keyMax(keys[end - 1])) : max;
    int chunkCount = (hi - lo + MAX_COUNT) / (MAX_COUNT + 1); // ceil((hi - lo) / 2048)
    int[] replKeys = new int[chunkCount];
    int w = 0;
    for (int chunkMin = lo; chunkMin < hi; chunkMin += MAX_COUNT + 1) {
      int chunkMax = Math.min(hi, chunkMin + MAX_COUNT + 1);
      replKeys[w] = packKey(chunkMin, chunkMax - chunkMin - 1);
      w++;
    }
    spliceWindow(start, end, replKeys);
    tryCoalesceAt(start + chunkCount - 1);
    tryCoalesceAt(start - 1);
  }

  /** Bulk-appends a single range; see {@link MutableCodePointSet#appendSorted}'s contract. */
  @Override
  public void appendSorted(int min, int max) {
    if (size > 0) {
      int lastIdx = size - 1;
      int lastKey = keys[lastIdx];
      if (keyMax(lastKey) == min) {
        int mergeLen = Math.min(MAX_COUNT - keyCount(lastKey), max - min);
        if (mergeLen > 0) {
          keys[lastIdx] = packKey(keyMin(lastKey), keyCount(lastKey) + mergeLen);
          min += mergeLen;
        }
      }
    }
    if (min >= max) {
      return;
    }
    int count = max - min - 1;
    if (count <= MAX_COUNT) {
      ensureCapacity(size + 1);
      keys[size] = packKey(min, count);
      size++;
      return;
    }
    int chunkCount = (max - min + MAX_COUNT) / (MAX_COUNT + 1);
    ensureCapacity(size + chunkCount);
    for (int chunkMin = min; chunkMin < max; chunkMin += MAX_COUNT + 1) {
      int chunkMax = Math.min(max, chunkMin + MAX_COUNT + 1);
      keys[size] = packKey(chunkMin, chunkMax - chunkMin - 1);
      size++;
    }
  }

  /** Replaces the entries at {@code [start, end)} with {@code replKeys}, shifting the tail as needed. */
  private void spliceWindow(int start, int end, int[] replKeys) {
    int delta = replKeys.length - (end - start);
    int oldSize = size;
    if (delta > 0) {
      ensureCapacity(size + delta);
    }
    if (delta != 0) {
      System.arraycopy(keys, end, keys, end + delta, oldSize - end);
    }
    System.arraycopy(replKeys, 0, keys, start, replKeys.length);
    size = oldSize + delta;
  }

  /** If the entries at {@code idx} and {@code idx + 1} are adjacent and merge within a single entry's capacity, merges them. */
  private void tryCoalesceAt(int idx) {
    if (idx < 0 || idx + 1 >= size) {
      return;
    }
    int a = keys[idx];
    int b = keys[idx + 1];
    if (keyMax(a) != keyMin(b)) {
      return;
    }
    int mergedCount = keyCount(a) + keyCount(b) + 1;
    if (mergedCount > MAX_COUNT) {
      return;
    }
    keys[idx] = packKey(keyMin(a), mergedCount);
    System.arraycopy(keys, idx + 2, keys, idx + 1, size - idx - 2);
    size--;
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
    // every real caller in this codebase builds these sets from scratch via appendSorted, not
    // addAll/union, so this path isn't hot.
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
}
