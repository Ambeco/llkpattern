package com.tbohne.llkpattern.impl.unicode;

import com.tbohne.llkpattern.impl.unicode.CodePointSet.MutableCodePointSet;
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
 * {@link #insert}/{@link #appendSorted}: two overlapping or touching entries always merge
 * unconditionally, with no value-equality check needed anywhere.
 */
public class ArrayCodePointSet implements MutableCodePointSet {
  // count occupies the low 11 bits (max 2047, i.e. entries span at most 2048 code points).
  private static final int COUNT_BITS = 11;
  // Package-private: CodePointSetBuilder.Impl needs it for chunk-count math.
  static final int MAX_COUNT = (1 << COUNT_BITS) - 1;

  // Shared by every empty set: a zero-length array, not null, so readers need no null check, and it can never be
  // written into (any real write grows it first).
  private static final int[] EMPTY_KEYS = new int[0];

  // Package-private: CodePointSetBuilder.Impl subclasses this class (see its doc) and reads/writes these fields
  // while accumulating (unsorted, via its overridden append) and in build() (sorting/coalescing in place). Kept
  // sorted by min (the key's high bits) ONCE an instance is a finished CodePointSet; every method below assumes
  // that. `size` is the entry count, `keys.length` the capacity (see ensureCapacity).
  int[] keys;
  public int size;

  // Whether `keys` ARE this set (false) or are EXCLUDED from it (true); see the class doc.
  boolean invert;

  public ArrayCodePointSet() {
    keys = EMPTY_KEYS;
    size = 0;
  }

  // Starts `keys` at initialCapacity for subclasses (CodePointSetBuilderImpl) whose typical accumulation is
  // bigger than the single-character common case, avoiding the first regrows.
  public ArrayCodePointSet(int initialCapacity) {
    keys = initialCapacity == 0 ? EMPTY_KEYS : new int[initialCapacity];
    size = 0;
  }

  @SuppressWarnings("method.invocation") // insertAllImpl is private and only touches this class's own (initialized) fields
  public ArrayCodePointSet(CodePointSet other) {
    this();
    insertAllImpl(other);
  }

  // The complement of source: an array copy plus a flipped invert bit. A static factory, not a constructor:
  // ArrayCodePointSet(ArrayCodePointSet) would out-specify the public copy constructor and make every
  // `new ArrayCodePointSet(this)` inside this class silently build a complement.
  private static ArrayCodePointSet complementOf(ArrayCodePointSet source) {
    ArrayCodePointSet result = new ArrayCodePointSet();
    result.keys = source.size == 0 ? EMPTY_KEYS : Arrays.copyOf(source.keys, source.size);
    result.size = source.size;
    result.invert = !source.invert;
    return result;
  }

  // Package-private: CodePointSetBuilder.Impl packs its own entries with it.
  static int packKey(int min, int count) {
    return (min << COUNT_BITS) | count;
  }

  // Package-private: CodePointSetBuilder.Impl unpacks its own entries with it in build().
  static int keyMin(int key) {
    return key >>> COUNT_BITS;
  }

  private static int keyCount(int key) {
    return key & MAX_COUNT;
  }

  static int keyMax(int key) { // exclusive
    return keyMin(key) + keyCount(key) + 1;
  }

  private static final int LINEAR_SEARCH_THRESHOLD = 65;

  // Index of the last entry whose min is <= codePoint, or -1 if none. Static, with keys/size as parameters: an
  // experiment on this hot leaf (contains/containsAll) that showed no measurable difference (notes.md); not a
  // style to chase elsewhere.
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
    if (!invert && size == 0) {
      return false;
    }
    if (other instanceof ArrayCodePointSet) {
      ArrayCodePointSet o = (ArrayCodePointSet) other;
      if (!o.invert) {
        if (o.size == 0) {
          return false;
        }
        if (invert || o.size <= SMALL_INTERSECT_SIZE) {
          return o.anyEntryOverlaps(this);
        }
        if (size <= SMALL_INTERSECT_SIZE) {
          return anyEntryOverlaps(o);
        }
        return sweepIntersects(o);
      }
      if (!invert) {
        return anyEntryOverlaps(o);
      }
    }
    return other.first(this::overlapsRange); // lazy or doubly-inverted operand
  }

  // At or below this many entries a set is cheaper to look up range by range in the other set
  // (one binary search each) than to walk both sets' entries in a merge sweep.
  private static final int SMALL_INTERSECT_SIZE = 4;

  /** Whether any of this (non-inverted) set's entries overlaps {@code searched}, by binary search. */
  private boolean anyEntryOverlaps(ArrayCodePointSet searched) {
    for (int i = 0; i < size; i++) {
      if (searched.overlapsRange(keyMin(keys[i]), keyMax(keys[i]))) {
        return true;
      }
    }
    return false;
  }

  /** {@link #intersects} for two non-inverted sets: one linear merge walk over both entry lists. */
  private boolean sweepIntersects(ArrayCodePointSet o) {
    int i = 0;
    int j = 0;
    while (i < size && j < o.size) {
      if (keyMax(keys[i]) <= keyMin(o.keys[j])) {
        i++;
      } else if (keyMax(o.keys[j]) <= keyMin(keys[i])) {
        j++;
      } else {
        return true;
      }
    }
    return false;
  }

  // Whether this set contains a code point in [min, max): the allocation-free half of intersects.
  private boolean overlapsRange(int min, int max) {
    if (!invert) {
      int idx = windowStart(min);
      return idx < size && keyMin(keys[idx]) < max;
    }
    // Inverted: members are the GAPS between entries; walk the window for a gap before an entry or after the last.
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

  // Like windowStart for insert/appendSorted's merge semantics: an entry merely touching min counts too, since it
  // is about to fuse with the new range.
  private int insertWindowStart(int min) {
    int idx = floorIndex(keys, size, min);
    if (idx < 0 || keyMax(keys[idx]) < min) {
      idx++;
    }
    return idx;
  }

  /** The {@link #insertWindowStart} counterpart of {@link #windowEnd} -- touching entries included. */
  private int insertWindowEnd(int start, int max) {
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
    // Inverted: empty only if the excluded entries cover the ENTIRE domain; no entries means everything.
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
      // Every code point in the window is "in" iff no excluded entry overlaps it.
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
   * An allocation-light two-pointer sweep over both sets' raw {@code keys}: one temporary set (the result),
   * versus the default's one per range plus a binary-search {@link #insert} per sub-range. Falls back to the
   * default for a non-{@link ArrayCodePointSet} (a lazy {@code UnionCodePointSet}); the parse-time hot path
   * ({@code &&}) always has concrete operands (see {@code CodePointSetBuilder#mergeRun}).
   *
   * <p>Handles {@link #invert} by De Morgan's laws, since {@code keys} holds the same raw ranges either way: two
   * normal sets sweep-intersect; two inverted sets give the inverted raw union; one of each is the normal
   * side's raw ranges minus the inverted side's.
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

  // a's and b's raw ranges, unioned via a linear merge. Coalesces into a running (curMin, curMax) before calling
  // appendSorted, NOT "append whichever next range has the smaller min": a run spanning more than 2048 code
  // points is split across physical chunks, so a later range can ascend yet fall behind the covered frontier,
  // violating appendSorted's "only touches the LAST entry" precondition (found by the differential fuzz test in
  // ArrayCodePointSetTest).
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
  public void insert(int min, int max) {
    if (invert) {
      // keys are the EXCLUDED runs, so adding to the set means un-excluding.
      removeRaw(min, max);
      return;
    }
    int start = insertWindowStart(min);
    int end = insertWindowEnd(start, max);
    insertRange(start, end, min, max);
  }

  /**
   * Bulk-appends a single range. Callers must supply ranges in ascending {@code min} order, building
   * this set up from empty.
   */
  void appendSorted(int min, int max) {
    if (min >= max) {
      return; // empty range -- guard needed since insertRange treats an empty [start,end) touching
              // the last entry as "shrink it away", not "no-op".
    }
    if (max - min <= MAX_COUNT + 1 && (size == 0 || keyMax(keys[size - 1]) < min)) {
      // Common case: a disjoint, single-chunk range just becomes one new trailing entry.
      ensureCapacity(size + 1);
      keys[size] = packKey(min, max - min - 1);
      size++;
      return;
    }
    // Sorted-append input can only ever touch/overlap the LAST existing entry (everything else is
    // strictly before it), so the merge window is found by a plain check instead of the binary
    // search insert() needs.
    int start = (size > 0 && keyMax(keys[size - 1]) >= min) ? size - 1 : size;
    insertRange(start, size, min, max);
  }

  // Replaces the entries at [start, end) (which touch or overlap [min, max), and nothing else does) with the
  // packed chunks covering their union, shifting the tail. Anything in the window merges into one wider run, so
  // there is no separate coalesce pass or intermediate array.
  private void insertRange(int start, int end, int min, int max) {
    int lo = (start < end) ? Math.min(min, keyMin(keys[start])) : min;
    int hi = (start < end) ? Math.max(max, keyMax(keys[end - 1])) : max;
    int chunkCount = (hi - lo + MAX_COUNT) / (MAX_COUNT + 1); // ceil((hi - lo) / 2048)
    int delta = chunkCount - (end - start);
    if (delta != 0) {
      // Growing needs room before the tail shifts; shrinking never does.
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
  public void insertAll(CodePointSet other) {
    insertAllImpl(other);
  }

  /** {@link #insertAll}'s body, non-overridable so the copy constructor can call it safely. */
  private void insertAllImpl(CodePointSet other) {
    if (other instanceof ArrayCodePointSet) {
      ArrayCodePointSet o = (ArrayCodePointSet) other;
      if (o == this) {
        return; // union with self -- also guards mergeInPlace below against aliasing its own keys
      }
      if (o.size == 0 && !o.invert) {
        return;
      }
      if (!invert && !o.invert) {
        if (size == 0) {
          // Fast path: a plain array copy, but keep `keys`' capacity if a caller pre-sized it
          // (mergeEntryPoints/unionLastCharSet): Arrays.copyOf would discard that sizing before later insertAlls
          // (notes.md 2026-09-25). Safe for a still-accumulating CodePointSetBuilderImpl, since a copy ignores
          // sort order.
          if (keys.length >= o.size) {
            System.arraycopy(o.keys, 0, keys, 0, o.size);
          } else {
            keys = Arrays.copyOf(o.keys, o.size);
          }
          size = o.size;
          return;
        }
        // General case, only when `keys` has room for both operands: shift this set's entries to the tail, then
        // sweep-merge forward into keys[0..], coalescing and re-chunking like CodePointSetBuilderImpl#build's
        // in-place compaction (the write cursor never passes the read cursor: k source chunks never need more
        // than k output chunks). This is the shape mergeEntryPoints/unionLastCharSet's capacity hints and
        // mergeRun's two-step combine produce.
        //
        // Deliberately NOT a sweepUnion fallback when `keys` lacks room: it always allocates a wrapper plus a
        // pessimistically sized array, worse than the per-range insert() fallback below (~0.9% desktop
        // compile-allocation regression; notes.md).
        //
        // Requires `keys` sorted, which a still-accumulating CodePointSetBuilderImpl is NOT (its append() is
        // unsorted until build()), so builders take the per-range insert() fallback. `o` can't be a live builder
        // (it isn't a CodePointSet until built); only `this` can.
        if (!(this instanceof CodePointSetBuilder) && keys.length >= size + o.size) {
          mergeInPlace(o);
          return;
        }
      }
    }
    // Fallback (a lazy UnionCodePointSet, or an inverted side): insert() per source range, which already merges
    // whatever it touches.
    if (!invert && other instanceof ArrayCodePointSet) {
      // Each insert() adds at most chunkBound entries: size once up front (an inverted source's few huge gap
      // ranges re-chunk into hundreds of entries).
      int needed = size + ((ArrayCodePointSet) other).memberChunkBound();
      if (keys.length < needed) {
        keys = Arrays.copyOf(keys, needed);
      }
    }
    other.forEachRange(this::insert);
  }

  // Capacity for an empty set that will insertAll(accept) then removeAll every `removes` entry but index `skip`,
  // so neither step regrows it. Each removed range adds at most one entry; an inverted operand emits at most
  // size + 1 ranges.
  public static int capacityHint(@Nullable CodePointSet accept, CodePointSet[] removes, int skip) {
    int capacity = accept instanceof ArrayCodePointSet ? ((ArrayCodePointSet) accept).memberChunkBound() : 0;
    for (int j = 0; j < removes.length; j++) {
      if (j != skip && removes[j] instanceof ArrayCodePointSet) {
        capacity += ((ArrayCodePointSet) removes[j]).size + 1;
      }
    }
    return capacity;
  }

  /** An upper bound on the packed entries {@link #forEachRange}'s ranges need once re-chunked. */
  private int memberChunkBound() {
    if (!invert) {
      return size;
    }
    int total = 0;
    int cursor = 0;
    for (int i = 0; i < size; i++) {
      int min = keyMin(keys[i]);
      if (min > cursor) {
        total += (min - cursor + MAX_COUNT) / (MAX_COUNT + 1);
      }
      cursor = keyMax(keys[i]);
    }
    if (cursor <= MAX_CODE_POINT) {
      total += (MAX_CODE_POINT + 1 - cursor + MAX_COUNT) / (MAX_COUNT + 1);
    }
    return total;
  }

  // insertAll's in-place fast path: the caller confirmed keys.length >= size + o.size and that neither side is
  // inverted or a builder. Shifts this set's entries to the tail ([o.size, o.size + size)), then sweep-merges
  // that copy and o.keys forward into keys[0..], coalescing before writing and re-chunking each run, as
  // CodePointSetBuilderImpl#build does (the shifted copy is never overwritten before it is read, since writing
  // starts at 0 and the copy at o.size).
  private void mergeInPlace(ArrayCodePointSet o) {
    int aStart = o.size;
    System.arraycopy(keys, 0, keys, aStart, size);
    int ai = aStart, aEnd = aStart + size;
    int bi = 0, bEnd = o.size;
    int w = 0;
    boolean haveCurrent = false;
    int curMin = 0, curMax = 0;
    while (ai < aEnd || bi < bEnd) {
      int min, max;
      if (bi >= bEnd || (ai < aEnd && keyMin(keys[ai]) <= keyMin(o.keys[bi]))) {
        min = keyMin(keys[ai]);
        max = keyMax(keys[ai]);
        ai++;
      } else {
        min = keyMin(o.keys[bi]);
        max = keyMax(o.keys[bi]);
        bi++;
      }
      if (!haveCurrent) {
        curMin = min;
        curMax = max;
        haveCurrent = true;
      } else if (min <= curMax) {
        curMax = Math.max(curMax, max);
      } else {
        for (int chunkMin = curMin; chunkMin < curMax; chunkMin += MAX_COUNT + 1) {
          int chunkMax = Math.min(curMax, chunkMin + MAX_COUNT + 1);
          keys[w] = packKey(chunkMin, chunkMax - chunkMin - 1);
          w++;
        }
        curMin = min;
        curMax = max;
      }
    }
    if (haveCurrent) {
      for (int chunkMin = curMin; chunkMin < curMax; chunkMin += MAX_COUNT + 1) {
        int chunkMax = Math.min(curMax, chunkMin + MAX_COUNT + 1);
        keys[w] = packKey(chunkMin, chunkMax - chunkMin - 1);
        w++;
      }
    }
    size = w;
  }

  @Override
  public CodePointSet union(CodePointSet other) {
    if (other instanceof ArrayCodePointSet && !invert && !(this instanceof CodePointSetBuilder)) {
      ArrayCodePointSet o = (ArrayCodePointSet) other;
      if (!o.invert && !(o instanceof CodePointSetBuilder)) {
        return sweepUnion(this, o);
      }
    }
    ArrayCodePointSet result = new ArrayCodePointSet(this);
    result.insertAll(other);
    return result;
  }

  @Override
  public CodePointSet difference(CodePointSet other) {
    if (other instanceof ArrayCodePointSet && !invert && !(this instanceof CodePointSetBuilder)) {
      ArrayCodePointSet o = (ArrayCodePointSet) other;
      if (!o.invert && !(o instanceof CodePointSetBuilder)) {
        return sweepDifference(this, o);
      }
    }
    ArrayCodePointSet result = new ArrayCodePointSet(this);
    result.removeAll(other);
    return result;
  }

  @Override
  public void remove(int min, int max) {
    if (!invert) {
      removeRaw(min, max);
    } else if (min < max) {
      // keys are the EXCLUDED runs, so removing from the set means excluding more.
      int start = insertWindowStart(min);
      int end = insertWindowEnd(start, max);
      insertRange(start, end, min, max);
    }
  }

  /** Removes {@code [min, max)} from {@code keys}' recorded runs, ignoring {@link #invert}. */
  private void removeRaw(int min, int max) {
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

  // Bulk difference. The default per-range loop splits entries one hole at a time, each split a potential
  // regrowth (the dominant cost in the compile-time allocation profile). Each removed range adds at most one
  // entry, so growing `keys` once to size + other.size makes every remove() below non-allocating. Kept per-range
  // (binary search plus a small memmove) rather than a linear sweep, which measured slower when `other` is small
  // next to `this`. Inverted operands, lazy unions and self use the default loop.
  @Override
  public void removeAll(CodePointSet other) {
    if (other instanceof ArrayCodePointSet && other != this && !invert
        && !((ArrayCodePointSet) other).invert && !(this instanceof CodePointSetBuilder)) {
      ArrayCodePointSet o = (ArrayCodePointSet) other;
      if (size == 0 || o.size == 0) {
        return;
      }
      if (keys.length < size + o.size) {
        keys = Arrays.copyOf(keys, size + o.size);
      }
      if ((o.size << 3) >= size) {
        subtractInPlace(o);
        return;
      }
    }
    MutableCodePointSet.super.removeAll(other);
  }

  // removeAll's single-pass path for two non-inverted sets, given keys.length >= size + o.size: shifts this set's
  // entries to the tail, then sweeps forward into keys[0..]. Each of o's ranges splits at most one entry, so
  // output never outruns the shifted read cursor.
  private void subtractInPlace(ArrayCodePointSet o) {
    int aStart = o.size;
    System.arraycopy(keys, 0, keys, aStart, size);
    int aEnd = aStart + size;
    int bi = 0;
    int w = 0;
    for (int ai = aStart; ai < aEnd; ai++) {
      int cursor = keyMin(keys[ai]);
      int aMax = keyMax(keys[ai]);
      while (cursor < aMax) {
        while (bi < o.size && keyMax(o.keys[bi]) <= cursor) {
          bi++;
        }
        if (bi >= o.size || keyMin(o.keys[bi]) >= aMax) {
          w = writeChunks(keys, w, cursor, aMax);
          cursor = aMax;
        } else {
          int bMin = keyMin(o.keys[bi]);
          if (cursor < bMin) {
            w = writeChunks(keys, w, cursor, bMin);
          }
          cursor = Math.max(cursor, keyMax(o.keys[bi]));
        }
      }
    }
    size = w;
  }

  /** Writes {@code [min, max)} as packed chunks at {@code keys[w..]}; returns the new write index. */
  private static int writeChunks(int[] dst, int w, int min, int max) {
    for (int chunkMin = min; chunkMin < max; chunkMin += MAX_COUNT + 1) {
      int chunkMax = Math.min(max, chunkMin + MAX_COUNT + 1);
      dst[w++] = packKey(chunkMin, chunkMax - chunkMin - 1);
    }
    return w;
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
   * A {@link CodePointSetBuilder} that IS an {@link ArrayCodePointSet} (see that interface's doc). Nested here
   * because the two are tightly intertwined: it reaches into the package-private {@code keys}/{@code
   * size}/{@code packKey}/{@code keyMin}/{@code keyMax}/{@code MAX_COUNT}, and the {@code (int
   * initialCapacity)} constructor exists for it.
   *
   * <p>{@link #append} is unsorted while accumulating (vs. {@link ArrayCodePointSet#insert}'s sorted insert).
   * {@link #insert} is redirected to it as a guard: the inherited one assumes sorted {@code keys}, which
   * doesn't hold mid-accumulation. {@link #appendAll} appends each source range (plus a packed-key copy when
   * still empty), never touching {@code insertAll}'s sorted-merge paths. {@link #invert} is overridden only to
   * add the build-once guard.
   */
  static final class CodePointSetBuilderImpl extends ArrayCodePointSet implements CodePointSetBuilder {
    // Bigger than ArrayCodePointSet's single-character default: a bracket expression's literal members are
    // typically a small handful of ranges, so starting bigger avoids the first regrows (notes.md has the
    // measurement history; an inheritance-based rewrite once lost this value).
    private static final int BUILDER_INITIAL_CAPACITY = 4;

    // Insertion sort shifts at most (first run length) x (second run length) entries; below this it beats the merge's temp-array copy.
    private static final int MERGE_MIN_SHIFTS = 256;

    private boolean built = false;

    CodePointSetBuilderImpl() {
      super(BUILDER_INITIAL_CAPACITY);
    }

    @Override
    public void add(int codePoint) {
      append(codePoint, codePoint + 1);
    }

    @Override
    public void append(int min, int max) {
      checkNotBuilt();
      // Packed immediately, not deferred to build(); a range wider than MAX_COUNT becomes several entries, as in
      // insertRange.
      for (int chunkMin = min; chunkMin < max; chunkMin += MAX_COUNT + 1) {
        int chunkMax = Math.min(max, chunkMin + MAX_COUNT + 1);
        appendKey(packKey(chunkMin, chunkMax - chunkMin - 1));
      }
    }

    // Redirects to append(): the inherited insert() assumes sorted `keys`, which this class doesn't maintain
    // until build(). append() already checks checkNotBuilt().
    @Override
    public void insert(int min, int max) {
      append(min, max);
    }

    private void appendKey(int key) {
      if (size == keys.length) {
        keys = Arrays.copyOf(keys, keys.length + (keys.length >> 1) + 1);
      }
      keys[size] = key;
      size++;
    }

    @Override
    public void appendAll(CodePointSet source) {
      checkNotBuilt();
      if (source instanceof ArrayCodePointSet) {
        ArrayCodePointSet o = (ArrayCodePointSet) source;
        if (size == 0 && !o.invert) {
          // Nothing accumulated yet, so a packed-key copy beats unpack-and-repack per range.
          if (keys.length >= o.size) {
            System.arraycopy(o.keys, 0, keys, 0, o.size);
          } else {
            keys = Arrays.copyOf(o.keys, o.size);
          }
          size = o.size;
          return;
        }
        // Each source range appends at most memberChunkBound() entries in total; size once.
        int needed = size + o.memberChunkBound();
        if (keys.length < needed) {
          keys = Arrays.copyOf(keys, needed);
        }
      }
      source.forEachRange(this::append);
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
      // One forward pass: each maximal run of touching/overlapping entries (by real extent, not their
      // pre-chunked boundaries) is re-chunked into as many packed entries as it needs, in the SAME array. The
      // write cursor never runs ahead of the read cursor: a run of N entries never needs more than N chunks.
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

    // If keys[0..size) is exactly two ascending runs (what appending one sorted set after another produces) and
    // both are long enough that insertion sort's shifts would add up, merges them linearly via a copy of the
    // shorter run and returns true; otherwise leaves keys untouched and returns false.
    private boolean mergeTwoRuns() {
      int split = 1;
      while (keyMin(keys[split]) >= keyMin(keys[split - 1])) {
        split++;
      }
      int tailLength = size - split;
      if (split * tailLength <= MERGE_MIN_SHIFTS) {
        return false;
      }
      for (int i = split + 1; i < size; i++) {
        if (keyMin(keys[i]) < keyMin(keys[i - 1])) {
          return false;
        }
      }
      if (split <= tailLength) {
        // Copy the shorter head; merge forward. The write cursor never passes the tail's read cursor.
        int[] head = Arrays.copyOfRange(keys, 0, split);
        int i = 0;
        int j = split;
        int k = 0;
        while (i < split) {
          if (j < size && keyMin(keys[j]) < keyMin(head[i])) {
            keys[k++] = keys[j++];
          } else {
            keys[k++] = head[i++];
          }
        }
        return true;
      }
      int[] tail = Arrays.copyOfRange(keys, split, size);
      int i = split - 1;
      int j = tailLength - 1;
      for (int k = size - 1; j >= 0; k--) {
        if (i >= 0 && keyMin(keys[i]) > keyMin(tail[j])) {
          keys[k] = keys[i--];
        } else {
          keys[k] = tail[j--];
        }
      }
      return true;
    }

    private void checkNotBuilt() {
      if (built) {
        throw new IllegalStateException(
            "CodePointSetBuilder already built -- create a new builder per CodePointSet instead "
                + "of reusing one (see class doc)");
      }
    }

    // Insertion sort by each entry's real (unpacked) min: input is small and near-sorted at every call site,
    // where its low constant factor beats Arrays.sort. Compares the extracted min, not raw packed ints:
    // min << 11 can overflow the sign bit from code point 0x100000.
    private void sortInPlaceByMin() {
      int prevMin = Integer.MIN_VALUE;
      int firstOutOfOrder = 0;
      for (; firstOutOfOrder < size; firstOutOfOrder++) {
        int min = keyMin(keys[firstOutOfOrder]);
        if (min < prevMin) {
          break;
        }
        prevMin = min;
      }
      // Already sorted (the common case) falls straight through; otherwise the sorted prefix is skipped.
      if (firstOutOfOrder < size && mergeTwoRuns()) {
        return;
      }
      for (int i = firstOutOfOrder; i < size; i++) {
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
