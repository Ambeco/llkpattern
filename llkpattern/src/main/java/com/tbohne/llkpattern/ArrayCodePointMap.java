package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import java.util.Arrays;
import java.util.Map.Entry;
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
 * ends) mapped to a single value. Entries are stored as {@code long keys[i]} packed as {@code (min
 * << 11) | count} -- {@code min} in the high 21 bits, {@code count} (the number of <i>additional</i>
 * code points after {@code min} sharing the value, so a single-code-point entry has {@code count ==
 * 0}) in the low 11 bits -- alongside a parallel {@code values[i]} array holding the value for that
 * entry. {@code keys} is kept sorted by {@code min}, so lookup is a binary search. Adjacent entries
 * with equal values are coalesced together on every mutation -- so, for example, two separate
 * {@code put} calls for touching ranges with the same value produce one entry, not two -- except
 * where doing so would exceed a single entry's 2048-code-point capacity (the 11-bit count field's
 * range): a range longer than that is unavoidably split across multiple consecutive entries, which
 * *is* observable through {@link #entrySet()} (each entry is at most 2048 code points long) even
 * though the whole range maps to one logical value throughout.
 *
 * <p>Unlike {@link TreeCodePointMap} (which delegates to Guava's {@code TreeRangeMap} and does not
 * coalesce at all), two separate {@code put} calls for adjacent ranges with the same value produce
 * a single coalesced entry here, not two (capacity permitting) -- this class's {@code equals}/
 * {@code entrySet} operate on that coalesced, canonical form.
 */
public final class ArrayCodePointMap<V> implements MutableCodePointMap<V> {
  // count occupies the low 11 bits (max 2047, i.e. entries span at most 2048 code points).
  private static final int COUNT_BITS = 11;
  private static final int MAX_COUNT = (1 << COUNT_BITS) - 1;

  private static final int INITIAL_CAPACITY = 16;

  // Parallel arrays, kept sorted by min (equivalently, by key, since min occupies the high bits
  // and comparisons here always extract min rather than comparing keys as raw ints -- see the
  // class doc's note on avoiding signed-int comparison pitfalls on the packed key itself).
  // `size` is the logical entry count; `keys.length`/`values.length` (always equal) are capacity,
  // which can run ahead of `size` -- see ensureCapacity -- so every access below is bounded by
  // `size`, never by the arrays' own length.
  private long[] keys;
  private V[] values;
  private int size;

  public ArrayCodePointMap() {
    keys = new long[INITIAL_CAPACITY];
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

  /** Grows the backing arrays (by 1.5x, or to {@code minCapacity} if that's bigger) if needed. */
  private void ensureCapacity(int minCapacity) {
    if (keys.length < minCapacity) {
      int newCapacity = Math.max(minCapacity, keys.length + (keys.length >> 1));
      keys = Arrays.copyOf(keys, newCapacity);
      values = Arrays.copyOf(values, newCapacity);
    }
  }

  private static long packKey(int min, int count) {
    return ((long) min << COUNT_BITS) | count;
  }

  private static int keyMin(long key) {
    return (int) (key >>> COUNT_BITS);
  }

  private static int keyCount(long key) {
    return (int) (key & MAX_COUNT);
  }

  private static int keyMax(long key) { // exclusive
    return keyMin(key) + keyCount(key) + 1;
  }

  /** Index of the last entry whose min is {@code <= codePoint}, or {@code -1} if none. */
  private int floorIndex(int codePoint) {
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

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public boolean containsKeys(int min, int max) {
    int cp = min;
    while (cp < max) {
      int idx = floorIndex(cp);
      if (idx < 0 || keyMax(keys[idx]) <= cp) {
        return false;
      }
      cp = keyMax(keys[idx]);
    }
    return true;
  }

  @Override
  public Set<Entry<Range, V>> entrySet() {
    Set<Entry<Range, V>> result = new java.util.LinkedHashSet<>();
    for (int i = 0; i < size; i++) {
      result.add(new ImmutableEntry<>(new Range(keyMin(keys[i]), keyMax(keys[i])), values[i]));
    }
    return result;
  }

  @Override
  public @PolyNull V getOrDefault(int codePoint, @Nullable V defaultValue) {
    int idx = floorIndex(codePoint);
    if (idx >= 0 && codePoint < keyMax(keys[idx])) {
      return values[idx];
    }
    return defaultValue;
  }

  @Override
  public CodePointMap<V> intersection(int min, int max) {
    ArrayCodePointMap<V> result = new ArrayCodePointMap<>();
    int idx = Math.max(0, floorIndex(min));
    for (int i = idx; i < size; i++) {
      long key = keys[i];
      int lo = Math.max(min, keyMin(key));
      int hi = Math.min(max, keyMax(key));
      if (lo < hi) {
        result.put(lo, hi, values[i]);
      }
      if (keyMin(key) >= max) {
        break;
      }
    }
    return result;
  }

  @Override
  public CodePointMap<V> intersectionRejectingConflicts(CodePointMap<V> other) {
    ArrayCodePointMap<V> result = new ArrayCodePointMap<>();
    for (Entry<Range, V> otherEntry : other.entrySet()) {
      int min = otherEntry.getKey().min;
      int max = otherEntry.getKey().max;
      int idx = Math.max(0, floorIndex(min));
      for (int i = idx; i < size && keyMin(keys[i]) < max; i++) {
        long key = keys[i];
        int lo = Math.max(min, keyMin(key));
        int hi = Math.min(max, keyMax(key));
        if (lo < hi) {
          V mine = values[i];
          if (!mine.equals(otherEntry.getValue())) {
            throw new CodePointMap.ConflictingMappingException(
                "this map has value "
                    + mine
                    + " for code points "
                    + new Range(lo, hi)
                    + ", but other map has value "
                    + otherEntry.getValue()
                    + " for code points "
                    + otherEntry.getKey());
          }
          result.put(lo, hi, mine);
        }
      }
    }
    return result;
  }

  @Override
  public void put(int min, int max, V value) {
    remove(min, max);
    // Split into <= MAX_COUNT+1-length chunks before inserting, so no single entry's count field
    // overflows.
    for (int chunkMin = min; chunkMin < max; chunkMin += MAX_COUNT + 1) {
      int chunkMax = Math.min(max, chunkMin + MAX_COUNT + 1);
      insert(chunkMin, chunkMax, value);
    }
    coalesceAround(min, max);
  }

  /** Inserts a single new entry {@code [min, max)}; caller guarantees no overlap with existing entries. */
  private void insert(int min, int max, V value) {
    int idx = floorIndex(min) + 1; // insertion point: first entry with min > `min`
    ensureCapacity(size + 1);
    System.arraycopy(keys, idx, keys, idx + 1, size - idx);
    System.arraycopy(values, idx, values, idx + 1, size - idx);
    keys[idx] = packKey(min, max - min - 1);
    values[idx] = value;
    size++;
  }

  /**
   * Merges adjacent entries with equal values. Coalescing only ever removes entries (never adds
   * any), so this always compacts safely in place: the write cursor never overtakes the read
   * cursor.
   */
  private void coalesceAround(int min, int max) {
    int writeIdx = 0;
    for (int i = 0; i < size; i++) {
      long key = keys[i];
      V value = values[i];
      if (writeIdx > 0) {
        long prevKey = keys[writeIdx - 1];
        V prevValue = values[writeIdx - 1];
        if (keyMax(prevKey) == keyMin(key) && Objects.equals(prevValue, value)) {
          int mergedCount = keyCount(prevKey) + keyCount(key) + 1;
          if (mergedCount <= MAX_COUNT) {
            keys[writeIdx - 1] = packKey(keyMin(prevKey), mergedCount);
            continue;
          }
        }
      }
      keys[writeIdx] = key;
      values[writeIdx] = value;
      writeIdx++;
    }
    for (int i = writeIdx; i < size; i++) {
      values[i] = null; // don't keep coalesced-away values reachable
    }
    size = writeIdx;
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

  @Override
  public void putAll(CodePointMap<V> other) {
    other.forEach((range, value) -> put(range.min, range.max, value));
  }

  @Override
  public void remove(int min, int max) {
    if (size == 0 || min >= max) {
      return;
    }
    // Entries are sorted and disjoint, so at most one entry can strictly contain [min, max) on
    // both sides -- the one case that turns 1 entry into 2 and so needs an extra slot. Every other
    // touched entry maps to 0 or 1 output entries, so the general loop below always has room to
    // compact in place (write cursor <= read cursor). Handling the growth case separately, up
    // front, keeps that loop simple and safe.
    int splitIdx = floorIndex(min);
    if (splitIdx >= 0) {
      long splitKey = keys[splitIdx];
      int entryMin = keyMin(splitKey);
      int entryMax = keyMax(splitKey);
      if (entryMin < min && entryMax > max) {
        V value = values[splitIdx];
        ensureCapacity(size + 1);
        System.arraycopy(keys, splitIdx + 1, keys, splitIdx + 2, size - splitIdx - 1);
        System.arraycopy(values, splitIdx + 1, values, splitIdx + 2, size - splitIdx - 1);
        keys[splitIdx] = packKey(entryMin, min - entryMin - 1);
        values[splitIdx] = value;
        keys[splitIdx + 1] = packKey(max, entryMax - max - 1);
        values[splitIdx + 1] = value;
        size++;
        return;
      }
    }
    int writeIdx = 0;
    for (int i = 0; i < size; i++) {
      long key = keys[i];
      V value = values[i];
      int entryMin = keyMin(key);
      int entryMax = keyMax(key);
      if (entryMax <= min || entryMin >= max) {
        keys[writeIdx] = key;
        values[writeIdx] = value;
        writeIdx++;
      } else if (entryMin < min) {
        keys[writeIdx] = packKey(entryMin, min - entryMin - 1);
        values[writeIdx] = value;
        writeIdx++;
      } else if (entryMax > max) {
        keys[writeIdx] = packKey(max, entryMax - max - 1);
        values[writeIdx] = value;
        writeIdx++;
      }
      // else: entry lies entirely within [min, max) -- fully removed, contributes nothing.
    }
    for (int i = writeIdx; i < size; i++) {
      values[i] = null; // don't keep removed values reachable
    }
    size = writeIdx;
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
