package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;

/**
 * A {@link CodePointMap} implementation specialized to the Unicode code point domain, backed by
 * two parallel arrays rather than a general-purpose range map. See {@link CodePointMap} for the
 * {@code [min, max)} range convention this class follows.
 *
 * <p>Each entry is a contiguous run of code points {@code [min, min + count]} (inclusive of both
 * ends) mapped to a single value. Entries are stored as {@code int keys[i]} packed as {@code (min
 * << 11) | count} -- {@code min} (21 bits, enough for the full {@code [0, MAX_CODE_POINT]} range)
 * in the high bits, {@code count} (the number of <i>additional</i> code points after {@code min}
 * sharing the value, so a single-code-point entry has {@code count == 0}) in the low 11 bits --
 * alongside a parallel {@code values[i]} array holding the value for that entry. 21+11 bits fit
 * exactly in an {@code int}, so no {@code long} is needed despite {@code min}'s high bit landing on
 * the sign bit for code points at/above {@code 0x100000} (plane 16) -- every read extracts {@code
 * min} via the unsigned {@code >>>} shift rather than ever comparing packed keys directly, which
 * sidesteps that entirely. {@code keys} is kept sorted by {@code min}, so lookup is a binary
 * search.
 *
 * <p>Every mutator ({@link #put}, {@link #remove}, {@link #appendSorted}) is localized to the
 * region of the array it actually touches (found via the same binary search lookup uses), not a
 * scan of the whole map -- building up an n-entry map via n {@code put}/{@code appendSorted} calls
 * is therefore O(n) amortized, not O(n^2). Adjacent entries with equal values are coalesced
 * together where doing so doesn't exceed a single entry's 2048-code-point capacity (the 11-bit
 * count field's range); a range longer than that is unavoidably split across multiple consecutive
 * entries, which *is* observable through {@link #entrySet()} even though it maps to one logical
 * value throughout. Unlike {@link TreeCodePointMap} (whose Guava {@code TreeRangeMap} backing never
 * auto-coalesces), that means two separate {@code put} calls for adjacent ranges with the same
 * value produce one entry here, not two (capacity permitting) -- this class's {@code equals}/
 * {@code entrySet} operate on that coalesced, canonical form.
 *
 * <p>The backing arrays start at capacity 1 (most instances here are small -- see {@link
 * PatternConstruct}, the only real caller, which builds one of these per union/loop-dispatch node)
 * and grow by 1.5x via {@link #ensureCapacity} as needed; call {@link #ensureCapacity} directly
 * first if the eventual size is known ahead of time, to skip the growth altogether.
 */
public final class ArrayCodePointMap<V> implements MutableCodePointMap<V> {
  // count occupies the low 11 bits (max 2047, i.e. entries span at most 2048 code points).
  private static final int COUNT_BITS = 11;
  private static final int MAX_COUNT = (1 << COUNT_BITS) - 1;

  private static final int INITIAL_CAPACITY = 1;

  // Parallel arrays, kept sorted by min (equivalently, by key, since min occupies the high bits
  // and comparisons here always extract min rather than comparing keys as raw ints -- see the
  // class doc's note on avoiding signed-int comparison pitfalls on the packed key itself).
  // `size` is the logical entry count; `keys.length`/`values.length` (always equal) are capacity,
  // which can run ahead of `size` -- see ensureCapacity -- so every access below is bounded by
  // `size`, never by the arrays' own length.
  private int[] keys;
  private V[] values;
  private int size;

  // The value for every code point *not* covered by an entry above, or null for an ordinary map
  // (see CodePointMap#getElseValue). A `values[i] == null` entry is the complementary case: a
  // "punched hole" explicitly excluding [min,max) from the else-value fill, built only by the
  // complement constructor below -- never by put()/appendSorted(), which reject null values from
  // ordinary callers (see put()'s null-check) since a null entry is meaningless without elseValue
  // != null to punch a hole in.
  private @Nullable V elseValue;

  public ArrayCodePointMap() {
    keys = new int[INITIAL_CAPACITY];
    values = newValuesArray(INITIAL_CAPACITY);
    size = 0;
  }

  @SuppressWarnings("unchecked")
  private static <V> V[] newValuesArray(int length) {
    return (V[]) new Object[length];
  }

  public ArrayCodePointMap(CodePointMap<V> other) {
    this();
    putAll(other);
  }

  /**
   * Builds the complement of {@code source}: {@code value} for every code point {@code source}
   * has no mapping for, nothing for every code point it does. Package-private -- reached only via
   * {@link CodePointMap#complement}, which is the type-safe public entry point.
   *
   * <p>This is always finite: {@code source.entrySet()} already resolves any else-value fill
   * {@code source} itself has into concrete entries (see {@link #entrySet()} below), so punching
   * a hole for each is exactly "not source" over the whole (bounded) code point domain -- no
   * unbounded enumeration, unlike Guava {@code RangeSet#complement()}.
   */
  ArrayCodePointMap(CodePointMap<V> source, V value) {
    this();
    elseValue = value;
    ensureCapacity(source.entrySet().size());
    for (Entry<Range, V> e : source.entrySet()) {
      appendSorted(e.getKey().min, e.getKey().max, null);
    }
  }

  @Override
  public @Nullable V getElseValue() {
    return elseValue;
  }

  /** Grows the backing arrays (by 1.5x, or to {@code minCapacity} if that's bigger) if needed. */
  @Override
  public void ensureCapacity(int minCapacity) {
    if (keys.length < minCapacity) {
      int newCapacity = Math.max(minCapacity, keys.length + (keys.length >> 1) + 1);
      keys = Arrays.copyOf(keys, newCapacity);
      values = Arrays.copyOf(values, newCapacity);
    }
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
  // EXPERIMENT (2026-09-08): linear-scan small maps instead of binary-searching them, to see
  // whether avoiding binary search's branch/indirection overhead helps given most maps here are
  // tiny -- see remaining_work.md's profiling item. Not yet decided as a keeper.
  private static final int LINEAR_SEARCH_THRESHOLD = 65;

  private int floorIndex(int codePoint) {
    if (size < LINEAR_SEARCH_THRESHOLD) {
      for (int i = size - 1; i >= 0; i--) {
        if (keyMin(keys[i]) <= codePoint) {
          return i;
        }
      }
      return -1;
    }
    int lo = 0;
    int hi = size - 1;
    int result = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (keyMin(keys[mid]) <= codePoint) {
        result = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return result;
  }

  /**
   * The half-open index range {@code [start, end)} of existing entries overlapping {@code [min,
   * max)}, found via {@link #floorIndex} rather than a scan -- entries are sorted and disjoint, so
   * this is always a contiguous run.
   */
  private int windowStart(int min) {
    int idx = floorIndex(min);
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
    return size == 0;
  }

  @Override
  public boolean containsKeys(int min, int max) {
    int cp = min;
    while (cp < max) {
      int idx = floorIndex(cp);
      if (idx >= 0 && cp < keyMax(keys[idx])) {
        if (values[idx] == null) {
          return false; // an explicitly-punched hole -- no mapping here regardless of elseValue
        }
        cp = keyMax(keys[idx]);
      } else if (elseValue != null) {
        int nextIdx = idx + 1;
        cp = (nextIdx < size) ? Math.min(max, keyMin(keys[nextIdx])) : max;
      } else {
        return false;
      }
    }
    return true;
  }

  @Override
  public Set<Entry<Range, V>> entrySet() {
    if (elseValue == null) {
      // A lazy view, not an eager copy: entrySet() itself is O(1), and iterating/short-circuiting
      // (e.g. PatternConstruct.findFirstOverlap's early return) costs only what it actually
      // visits. AbstractSet supplies Set-contract equals()/hashCode() (size + per-element
      // comparison) from just size()/iterator(), which is what this class's own equals()/
      // hashCode() rely on.
      return new AbstractSet<Entry<Range, V>>() {
        @Override
        public Iterator<Entry<Range, V>> iterator() {
          return new Iterator<Entry<Range, V>>() {
            private int i = 0;

            @Override
            public boolean hasNext() {
              return i < size;
            }

            @Override
            public Entry<Range, V> next() {
              if (i >= size) {
                throw new NoSuchElementException();
              }
              Entry<Range, V> entry = new ImmutableEntry<>(new Range(keyMin(keys[i]), keyMax(keys[i])), values[i]);
              i++;
              return entry;
            }
          };
        }

        @Override
        public int size() {
          return size;
        }
      };
    }
    // elseValue != null: gaps between (and around) the explicit entries are real mappings too, so
    // this must be materialized rather than a lazy view over the raw array -- see the class doc
    // on ComplementCodePointMap-style maps in CodePointMap#getElseValue. Bounded by the code point
    // domain, so still always finite: at most `size + 1` gap entries.
    return new LinkedHashSet<>(materializeWithGaps());
  }

  private List<Entry<Range, V>> materializeWithGaps() {
    List<Entry<Range, V>> result = new ArrayList<>();
    int cursor = 0;
    for (int i = 0; i < size; i++) {
      int entryMin = keyMin(keys[i]);
      int entryMax = keyMax(keys[i]);
      if (cursor < entryMin) {
        result.add(new ImmutableEntry<>(new Range(cursor, entryMin), elseValue));
      }
      if (values[i] != null) {
        result.add(new ImmutableEntry<>(new Range(entryMin, entryMax), values[i]));
      }
      cursor = entryMax;
    }
    if (cursor <= MAX_CODE_POINT) {
      result.add(new ImmutableEntry<>(new Range(cursor, MAX_CODE_POINT + 1), elseValue));
    }
    return result;
  }

  @Override
  public @PolyNull V getOrDefault(int codePoint, @Nullable V defaultValue) {
    int idx = floorIndex(codePoint);
    if (idx >= 0 && codePoint < keyMax(keys[idx])) {
      V raw = values[idx];
      return raw != null ? raw : defaultValue; // a punched hole is "no mapping", not elseValue
    }
    return elseValue != null ? elseValue : defaultValue;
  }

  /**
   * This map's entries overlapping {@code [min, max)}, clipped to that window. When {@code
   * elseValue == null} this is the fast raw-array window scan every other mutator uses; when it's
   * set, the raw array alone doesn't include the gap-fill (see {@link #entrySet()}), so this falls
   * back to {@link #materializeWithGaps()} instead -- correct either way, just not O(window size)
   * in the complement case, which is fine since {@code intersection}/{@code
   * intersectionRejectingConflicts} aren't on the hot match-time path.
   */
  private List<Entry<Range, V>> entriesOverlapping(int min, int max) {
    List<Entry<Range, V>> result = new ArrayList<>();
    if (elseValue == null) {
      int start = windowStart(min);
      int end = windowEnd(start, max);
      for (int i = start; i < end; i++) {
        int lo = Math.max(min, keyMin(keys[i]));
        int hi = Math.min(max, keyMax(keys[i]));
        if (lo < hi) {
          result.add(new ImmutableEntry<>(new Range(lo, hi), values[i]));
        }
      }
    } else {
      for (Entry<Range, V> e : materializeWithGaps()) {
        if (e.getKey().min >= max) {
          break; // ascending order -- nothing further can overlap
        }
        int lo = Math.max(min, e.getKey().min);
        int hi = Math.min(max, e.getKey().max);
        if (lo < hi) {
          result.add(new ImmutableEntry<>(new Range(lo, hi), e.getValue()));
        }
      }
    }
    return result;
  }

  @Override
  public CodePointMap<V> intersection(int min, int max) {
    ArrayCodePointMap<V> result = new ArrayCodePointMap<>();
    List<Entry<Range, V>> overlapping = entriesOverlapping(min, max);
    result.ensureCapacity(overlapping.size());
    for (Entry<Range, V> e : overlapping) {
      result.appendSorted(e.getKey().min, e.getKey().max, e.getValue());
    }
    return result;
  }

  @Override
  public CodePointMap<V> intersectionRejectingConflicts(CodePointMap<V> other) {
    ArrayCodePointMap<V> result = new ArrayCodePointMap<>();
    for (Entry<Range, V> otherEntry : other.entrySet()) {
      for (Entry<Range, V> mineEntry : entriesOverlapping(otherEntry.getKey().min, otherEntry.getKey().max)) {
        V mine = mineEntry.getValue();
        if (!mine.equals(otherEntry.getValue())) {
          throw new CodePointMap.ConflictingMappingException(
              "this map has value "
                  + mine
                  + " for code points "
                  + mineEntry.getKey()
                  + ", but other map has value "
                  + otherEntry.getValue()
                  + " for code points "
                  + otherEntry.getKey());
        }
        result.put(mineEntry.getKey().min, mineEntry.getKey().max, mine);
      }
    }
    return result;
  }

  @Override
  public CodePointMap<V> complement(V value) {
    // Overrides CodePointMap.complement's default (new TreeCodePointMap<>(this, value)) for the
    // same reason union/difference do -- stay in this concrete type rather than silently handing
    // back a TreeCodePointMap.
    return new ArrayCodePointMap<>(this, value);
  }

  @Override
  public void put(int min, int max, V value) {
    if (value == null) {
      // null is reserved internally for the "punched hole" complement() builds -- see the
      // elseValue field doc -- and is meaningless from an ordinary caller, which never has an
      // elseValue to punch a hole in. Reject it loudly rather than silently misbehaving.
      throw new NullPointerException(
          "ArrayCodePointMap does not support null values; did you mean complement(value) to "
              + "build the set of code points this map doesn't contain?");
    }
    int start = windowStart(min);
    int end = windowEnd(start, max);
    boolean keepLeft = start < end && keyMin(keys[start]) < min;
    boolean keepRight = start < end && keyMax(keys[end - 1]) > max;
    int chunkCount = (max - min + MAX_COUNT) / (MAX_COUNT + 1); // ceil((max - min) / 2048)
    int replCount = (keepLeft ? 1 : 0) + chunkCount + (keepRight ? 1 : 0);

    int[] replKeys = new int[replCount];
    V[] replValues = newValuesArray(replCount);
    int w = 0;
    if (keepLeft) {
      int entryMin = keyMin(keys[start]);
      replKeys[w] = packKey(entryMin, min - entryMin - 1);
      replValues[w] = values[start];
      w++;
    }
    for (int chunkMin = min; chunkMin < max; chunkMin += MAX_COUNT + 1) {
      int chunkMax = Math.min(max, chunkMin + MAX_COUNT + 1);
      replKeys[w] = packKey(chunkMin, chunkMax - chunkMin - 1);
      replValues[w] = value;
      w++;
    }
    if (keepRight) {
      int entryMax = keyMax(keys[end - 1]);
      replKeys[w] = packKey(max, entryMax - max - 1);
      replValues[w] = values[end - 1];
      w++;
    }

    spliceWindow(start, end, replKeys, replValues);
    // The only new adjacencies this put() could have created are at the two edges of the spliced
    // region -- anything already coalesced elsewhere in the map is untouched. Check the right edge
    // first so a merge there can't shift the still-unchecked left edge's indices.
    tryCoalesceAt(start + replCount - 1);
    tryCoalesceAt(start - 1);
  }

  /** Bulk-appends a single entry; see {@link MutableCodePointMap#appendSorted}'s contract. */
  @Override
  public void appendSorted(int min, int max, V value) {
    if (size > 0) {
      int lastIdx = size - 1;
      int lastKey = keys[lastIdx];
      if (keyMax(lastKey) == min && Objects.equals(values[lastIdx], value)) {
        int mergeLen = Math.min(MAX_COUNT - keyCount(lastKey), max - min);
        if (mergeLen > 0) {
          keys[lastIdx] = packKey(keyMin(lastKey), keyCount(lastKey) + mergeLen);
          min += mergeLen;
        }
      }
    }
    for (int chunkMin = min; chunkMin < max; chunkMin += MAX_COUNT + 1) {
      int chunkMax = Math.min(max, chunkMin + MAX_COUNT + 1);
      ensureCapacity(size + 1);
      keys[size] = packKey(chunkMin, chunkMax - chunkMin - 1);
      values[size] = value;
      size++;
    }
  }

  /** Replaces the entries at {@code [start, end)} with {@code replKeys}/{@code replValues}, shifting the tail as needed. */
  private void spliceWindow(int start, int end, int[] replKeys, V[] replValues) {
    int delta = replKeys.length - (end - start);
    int oldSize = size;
    if (delta > 0) {
      ensureCapacity(size + delta);
    }
    if (delta != 0) {
      System.arraycopy(keys, end, keys, end + delta, oldSize - end);
      System.arraycopy(values, end, values, end + delta, oldSize - end);
    }
    System.arraycopy(replKeys, 0, keys, start, replKeys.length);
    System.arraycopy(replValues, 0, values, start, replValues.length);
    size = oldSize + delta;
    for (int i = size; i < oldSize; i++) {
      values[i] = null; // don't keep replaced-away values reachable
    }
  }

  /**
   * If the entries at {@code idx} and {@code idx + 1} are adjacent, equal-valued, and merge within
   * a single entry's capacity, merges them (shrinking the map by one entry).
   */
  private void tryCoalesceAt(int idx) {
    if (idx < 0 || idx + 1 >= size) {
      return;
    }
    int a = keys[idx];
    int b = keys[idx + 1];
    if (keyMax(a) != keyMin(b) || !Objects.equals(values[idx], values[idx + 1])) {
      return;
    }
    int mergedCount = keyCount(a) + keyCount(b) + 1;
    if (mergedCount > MAX_COUNT) {
      return;
    }
    keys[idx] = packKey(keyMin(a), mergedCount);
    System.arraycopy(keys, idx + 2, keys, idx + 1, size - idx - 2);
    System.arraycopy(values, idx + 2, values, idx + 1, size - idx - 2);
    values[size - 1] = null;
    size--;
  }

  @Override
  public @Nullable V compute(int codePoint, CodePointRemapFunction<V> remappingFunction) {
    V before = get(codePoint);
    V after = remappingFunction.apply(codePoint, before);
    if (after == null) {
      remove(codePoint, codePoint + 1);
    } else {
      put(codePoint, codePoint + 1, after);
    }
    return before;
  }

  /**
   * Merges {@code other}'s entries into this map, {@code other} winning on overlap -- see {@link
   * MutableCodePointMap#putAll}. Implemented as a single sorted sweep over this map's own entries
   * (already sorted) and {@code other.entrySet()} (sorted per {@link CodePointMap}'s ordering
   * contract) rather than one {@link #put} call per source entry: each {@code other} entry is
   * appended as-is, and whatever of this map's own entries falls outside every {@code other}
   * entry -- the parts {@code other} doesn't overwrite -- is appended around it. This is O(this
   * map's size + other's size); when this map starts empty, the sweep degenerates to appending
   * every {@code other} entry directly; no separate fast path is needed for that case.
   */
  @Override
  public void putAll(CodePointMap<V> other) {
    if (other.isEmpty()) {
      return;
    }
    int[] oldKeys = keys;
    V[] oldValues = values;
    int oldSize = size;
    keys = new int[INITIAL_CAPACITY];
    values = newValuesArray(INITIAL_CAPACITY);
    size = 0;
    ensureCapacity(oldSize + other.entrySet().size()); // upper bound on the merged result's size

    // `i` is the next not-yet-fully-emitted entry of this map's original data; a "pending"
    // in-progress one (possibly a partial leftover after being trimmed by an `other` entry) is
    // tracked in pendingMin/pendingMax/pendingValue rather than mutating oldKeys/oldValues.
    int i = 0;
    boolean pending = false;
    int pendingMin = 0;
    int pendingMax = 0;
    V pendingValue = null;
    for (Entry<Range, V> otherEntry : other.entrySet()) {
      int oMin = otherEntry.getKey().min;
      int oMax = otherEntry.getKey().max;
      // Emit whatever of this map's original data lies entirely before oMin.
      while (true) {
        if (!pending) {
          if (i >= oldSize) {
            break;
          }
          pendingMin = keyMin(oldKeys[i]);
          pendingMax = keyMax(oldKeys[i]);
          pendingValue = oldValues[i];
          pending = true;
          i++;
        }
        if (pendingMin >= oMin) {
          break; // this pending entry starts at/after oMin -- nothing left to emit before it
        }
        int emitMax = Math.min(pendingMax, oMin);
        appendSorted(pendingMin, emitMax, pendingValue);
        if (emitMax >= pendingMax) {
          pending = false;
        } else {
          pendingMin = emitMax; // the rest overlaps `other`; handled by the loop below
        }
      }
      appendSorted(oMin, oMax, otherEntry.getValue());
      // Discard whatever of this map's original data `other`'s entry just overwrote.
      while (true) {
        if (!pending) {
          if (i >= oldSize) {
            break;
          }
          pendingMin = keyMin(oldKeys[i]);
          pendingMax = keyMax(oldKeys[i]);
          pendingValue = oldValues[i];
          pending = true;
          i++;
        }
        if (pendingMin >= oMax) {
          break; // doesn't overlap this `other` entry -- leave it for a later one, or the tail
        }
        if (pendingMax <= oMax) {
          pending = false; // fully overwritten
        } else {
          pendingMin = oMax; // partially overwritten; the remainder starts right after `other`
          break;
        }
      }
    }
    // Emit whatever of this map's original data is left after the last `other` entry.
    if (pending) {
      appendSorted(pendingMin, pendingMax, pendingValue);
    }
    while (i < oldSize) {
      appendSorted(keyMin(oldKeys[i]), keyMax(oldKeys[i]), oldValues[i]);
      i++;
    }
  }

  @Override
  public CodePointMap<V> union(CodePointMap<V> other) {
    // Overrides CodePointMap.union's default, which hardcodes `new TreeCodePointMap<>(this)`
    // regardless of the receiver's actual type -- calling it here would silently hand back a
    // TreeCodePointMap instead of an ArrayCodePointMap (the same bug PatternConstruct used to have
    // via an unchecked cast on this exact method -- see notes.md).
    ArrayCodePointMap<V> result = new ArrayCodePointMap<>(this);
    result.putAll(other);
    return result;
  }

  @Override
  public CodePointMap<V> difference(CodePointMap<V> other) {
    ArrayCodePointMap<V> result = new ArrayCodePointMap<>(this);
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
      return; // nothing overlaps [min, max)
    }
    if (end - start == 1) {
      // A single entry is touched: every case below is a plain field edit or a lone insert/
      // delete, never the general multi-entry splice -- no scratch arrays needed.
      int key = keys[start];
      int entryMin = keyMin(key);
      int entryMax = keyMax(key);
      boolean keepLeft = entryMin < min;
      boolean keepRight = entryMax > max;
      if (keepLeft && keepRight) {
        // Removing the middle: shrink this entry down to its left remainder, then insert the
        // right remainder right after it.
        keys[start] = packKey(entryMin, min - entryMin - 1);
        insertSingle(start + 1, packKey(max, entryMax - max - 1), values[start]);
      } else if (keepLeft) {
        // Removing the end of the range: just shorten this entry -- no shift needed.
        keys[start] = packKey(entryMin, min - entryMin - 1);
      } else if (keepRight) {
        // Removing the start of the range: just move this entry's start forward -- no shift
        // needed.
        keys[start] = packKey(max, entryMax - max - 1);
      } else {
        // The whole entry is removed.
        deleteRange(start, start + 1);
      }
      return;
    }
    // Multiple entries are touched: right-trim the first (if `min` falls inside it) and left-trim
    // the last (if `max` falls inside it) in place, then delete whatever's strictly between (and
    // now possibly the first/last themselves, if they weren't trimmed) in one shift. This can only
    // shrink the map -- the one case that grows it (a single entry split in two) is handled above.
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

  /** Shifts the tail down over {@code [from, to)}, removing those entries. */
  private void deleteRange(int from, int to) {
    if (from >= to) {
      return;
    }
    System.arraycopy(keys, to, keys, from, size - to);
    System.arraycopy(values, to, values, from, size - to);
    int newSize = size - (to - from);
    for (int i = newSize; i < size; i++) {
      values[i] = null; // don't keep removed values reachable
    }
    size = newSize;
  }

  /** Inserts one new entry at {@code idx}, shifting the tail up to make room. */
  private void insertSingle(int idx, int key, V value) {
    ensureCapacity(size + 1);
    System.arraycopy(keys, idx, keys, idx + 1, size - idx);
    System.arraycopy(values, idx, values, idx + 1, size - idx);
    keys[idx] = key;
    values[idx] = value;
    size++;
  }

  @Override
  public String toString() {
    return entrySet().toString();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (!(other instanceof CodePointMap)) {
      return false;
    }
    return entrySet().equals(((CodePointMap<?>) other).entrySet());
  }

  @Override
  public int hashCode() {
    return entrySet().hashCode();
  }
}
