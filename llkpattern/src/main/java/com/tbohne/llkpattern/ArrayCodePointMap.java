package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;
import com.tbohne.llkpattern.CodePointMap.RangeConsumer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
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
   * Builds a map directly from {@code count} already-sorted-by-min, pairwise-disjoint,
   * coalesced-where-possible ranges -- the one packing/chunking pass any {@code appendSorted}
   * loop would do, but into a single correctly-sized {@code keys}/{@code values} array computed
   * up front, instead of growing via {@link #ensureCapacity} as it goes. Package-private -- reached
   * only via {@link CodePointMapBuilder#build}, which has already done the sort/merge/conflict-
   * check work this constructor's preconditions assume; nothing else should call this directly.
   *
   * <p>Only {@code sortedMins}/{@code sortedMaxs}[0, count)} are read -- {@code sortedValues} is
   * {@code Object[]}, not {@code V[]}, purely because {@link CodePointMapBuilder} (a different
   * generic class) can't materialize a real {@code V[]} either (see its own {@code newValuesArray}-
   * style comment) -- the unchecked cast here is the same one every other {@code Object[]}-backed
   * value array in this file already needs.
   */
  ArrayCodePointMap(int[] sortedMins, int[] sortedMaxs, Object[] sortedValues, int count) {
    int chunkTotal = 0;
    for (int i = 0; i < count; i++) {
      chunkTotal += (sortedMaxs[i] - sortedMins[i] + MAX_COUNT) / (MAX_COUNT + 1); // ceil(/2048)
    }
    keys = new int[chunkTotal];
    values = newValuesArray(chunkTotal);
    size = 0;
    for (int i = 0; i < count; i++) {
      int min = sortedMins[i];
      int max = sortedMaxs[i];
      @SuppressWarnings("unchecked")
      V value = (V) sortedValues[i];
      for (int chunkMin = min; chunkMin < max; chunkMin += MAX_COUNT + 1) {
        int chunkMax = Math.min(max, chunkMin + MAX_COUNT + 1);
        keys[size] = packKey(chunkMin, chunkMax - chunkMin - 1);
        values[size] = value;
        size++;
      }
    }
  }

  /**
   * Builds the complement of {@code source}: {@code elseValue} for every code point {@code
   * source} has no mapping for, nothing for every code point it does. Private -- reached only via
   * {@link #complement}, which always passes {@code this} (so {@code source} is always an
   * {@code ArrayCodePointMap}) and is itself the type-safe public entry point.
   *
   * <p>Reads {@code source}'s raw {@code keys}/{@code values} arrays directly rather than going
   * through {@code entrySet()} -- avoids an {@code Entry}/{@code Range} allocation per entry, and
   * (in the common case below) a whole-array copy instead of one {@code appendSorted} call per
   * entry. Direct field access on another instance of this same class is fine in Java (private
   * access is per-class, not per-instance).
   */
  private ArrayCodePointMap(ArrayCodePointMap<V> source, V elseValue) {
    if (source.elseValue == null) {
      // Common case: source has no punched holes of its own, so every source entry becomes a
      // punched hole here, and this map's own else-value fill covers every code point between
      // them. A straight array copy, no per-entry work at all -- this is what keeps
      // NamedCharClass's complement-based constants (RegexCharacterClass.DOT/D/H/S/V/W etc.) cheap
      // to declare: none of their sources are themselves else-valued.
      //
      // Every source.values[i] is guaranteed non-null here (never just assumed): a null-valued
      // entry is only ever produced by this very constructor, always together with setting
      // elseValue non-null in the same call (see the field's own doc) -- since source.elseValue
      // is null, source can't hold one. (setElseValue(null) resetting an already-else-valued map
      // back to null-with-leftover-holes would violate that, but nothing in this codebase does
      // that -- every setElseValue caller is a plain dispatch map built via populate()/putAll(),
      // never a complement-derived one.)
      this.elseValue = elseValue;
      this.keys = Arrays.copyOf(source.keys, source.size);
      this.values = newValuesArray(source.size); // all null, i.e. every entry a punched hole.
      this.size = source.size;
    } else {
      // source is itself an else-valued (complement) map. Its own else-value fill means source
      // HAS a mapping at every code point not covered by one of its entries, so the complement
      // must stay unmapped there too -- this map's own else-value is null, not `elseValue`, and
      // only source's own punched holes (where source explicitly has NO mapping, regardless of
      // its else-value) become real entries here.
      // source.size is an upper bound on how many entries we'll actually keep (only the
      // null-valued ones), so this may over-allocate slightly -- still just one allocation
      // either way, unlike starting at INITIAL_CAPACITY and growing into it via ensureCapacity.
      keys = new int[source.size];
      values = newValuesArray(source.size);
      for (int i = 0; i < source.size; i++) {
        if (source.values[i] == null) {
          appendSorted(keyMin(source.keys[i]), keyMax(source.keys[i]), elseValue);
        }
      }
    }
  }

  @Override
  public @Nullable V getElseValue() {
    return elseValue;
  }

  @Override
  public void setElseValue(@Nullable V value) {
    elseValue = value;
  }

  @Override
  public @Nullable V getExplicit(int codePoint) {
    int idx = floorIndex(codePoint);
    if (idx >= 0 && codePoint < keyMax(keys[idx])) {
      return values[idx]; // null here means a punched hole -- correctly "not explicit" either way
    }
    return null;
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
  // Hybrid search (2026-09-08): binary search narrows [lo, hi] down to a window of at most
  // LINEAR_SEARCH_THRESHOLD entries (most maps here are tiny, so this window is often the whole
  // map -- see the class doc), then a final linear scan of that window avoids binary search's
  // per-step branch/indirection overhead over the remainder.
  //
  // The invariant "the answer, if any, lies within [lo, hi]" holds throughout: keys is sorted by
  // min, so keyMin(keys[mid]) <= codePoint means every j <= mid also qualifies (the rightmost
  // qualifying index is >= mid, so mid itself is still a viable answer -- lo is set to mid, not
  // mid + 1, to keep it in the window), while keyMin(keys[mid]) > codePoint means every j >= mid
  // doesn't (mid is never the answer, so hi = mid - 1 safely excludes it). mid is rounded up
  // (`(lo + hi + 1) >>> 1`, not the usual floor) so the true branch (lo = mid) still makes
  // progress even when hi == lo + 1 -- a floor mid would get stuck re-picking lo forever.
  private static final int LINEAR_SEARCH_THRESHOLD = 65;

  private int floorIndex(int codePoint) {
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

  /**
   * {@link CodePointMap#forEachRange}, overridden to skip {@link #entrySet()} entirely: the
   * {@code elseValue == null} case (the common one -- see the class doc) reads {@code keys}/
   * {@code values} directly, with no {@code Range}, {@code Entry}, or {@code Iterator} allocated
   * per visited range, unlike {@code entrySet()}'s lazy view (which still allocates one {@code
   * Entry}/{@code Range} pair per {@code next()}). The {@code elseValue != null} case still has to
   * compute the same gap-fill {@link #materializeWithGaps()} does, but calls straight through to
   * {@code action} instead of collecting into a throwaway {@code List<Entry<Range, V>>} first.
   */
  @Override
  public void forEachRange(RangeConsumer<? super V> action) {
    if (elseValue == null) {
      for (int i = 0; i < size; i++) {
        action.accept(keyMin(keys[i]), keyMax(keys[i]), values[i]);
      }
      return;
    }
    int cursor = 0;
    for (int i = 0; i < size; i++) {
      int entryMin = keyMin(keys[i]);
      int entryMax = keyMax(keys[i]);
      if (cursor < entryMin) {
        action.accept(cursor, entryMin, elseValue);
      }
      if (values[i] != null) {
        action.accept(entryMin, entryMax, values[i]);
      }
      cursor = entryMax;
    }
    if (cursor <= MAX_CODE_POINT) {
      action.accept(cursor, MAX_CODE_POINT + 1, elseValue);
    }
  }

  /**
   * {@link CodePointMap#first}, overridden for the same reason as {@link #forEachRange}: reads
   * {@code keys}/{@code values} directly instead of allocating a {@code Range}/{@code Entry}/
   * {@code Iterator} per candidate range, with the added benefit (over {@code forEachRange}) of
   * actually stopping at the first match instead of visiting every remaining range regardless.
   */
  @Override
  public boolean first(RangePredicate<? super V> predicate) {
    if (elseValue == null) {
      for (int i = 0; i < size; i++) {
        if (predicate.test(keyMin(keys[i]), keyMax(keys[i]), values[i])) {
          return true;
        }
      }
      return false;
    }
    int cursor = 0;
    for (int i = 0; i < size; i++) {
      int entryMin = keyMin(keys[i]);
      int entryMax = keyMax(keys[i]);
      if (cursor < entryMin && predicate.test(cursor, entryMin, elseValue)) {
        return true;
      }
      if (values[i] != null && predicate.test(entryMin, entryMax, values[i])) {
        return true;
      }
      cursor = entryMax;
    }
    return cursor <= MAX_CODE_POINT && predicate.test(cursor, MAX_CODE_POINT + 1, elseValue);
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

  @Override
  public CodePointMap<V> intersection(int min, int max) {
    ArrayCodePointMap<V> result = new ArrayCodePointMap<>();
    if (elseValue == null) {
      // Fast raw-array window scan, same one every other mutator uses -- no intermediate
      // Range/Entry/List at all.
      int start = windowStart(min);
      int end = windowEnd(start, max);
      result.ensureCapacity(end - start);
      for (int i = start; i < end; i++) {
        int lo = Math.max(min, keyMin(keys[i]));
        int hi = Math.min(max, keyMax(keys[i]));
        if (lo < hi) {
          result.appendSorted(lo, hi, values[i]);
        }
      }
      return result;
    }
    // elseValue != null: the gaps between entries are real mappings too (see entrySet()'s own
    // doc), so this walks entries starting from windowStart(min), filling each gap with elseValue
    // as it goes -- windowed the same way the elseValue == null case above is (windowStart/
    // windowEnd don't care about null-valued punched-hole entries either way), rather than
    // scanning from index 0 via materializeWithGaps()/entrySet() and throwing away everything
    // outside [min, max).
    int cursor = min;
    for (int i = windowStart(min); i < size && cursor < max; i++) {
      int entryMin = keyMin(keys[i]);
      if (entryMin >= max) {
        break; // ascending order -- nothing further can overlap
      }
      int entryMax = keyMax(keys[i]);
      if (cursor < entryMin) {
        result.appendSorted(cursor, Math.min(entryMin, max), elseValue);
      }
      if (values[i] != null) {
        int lo = Math.max(min, entryMin);
        int hi = Math.min(max, entryMax);
        if (lo < hi) {
          result.appendSorted(lo, hi, values[i]);
        }
      }
      cursor = entryMax;
    }
    if (cursor < max) {
      result.appendSorted(cursor, max, elseValue);
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
    if (min >= max) {
      return; // fully absorbed into the previous entry above.
    }
    int count = max - min - 1;
    if (count <= MAX_COUNT) {
      // Common case (a single code point, or any run short enough to fit one packed entry): skip
      // the chunk-count division and loop setup below entirely -- this is the overwhelming
      // majority of real calls (one character, or one small range, at a time), so a plain O(1)
      // insert here matters more than the general form's generality.
      ensureCapacity(size + 1);
      keys[size] = packKey(min, count);
      values[size] = value;
      size++;
      return;
    }
    // Rare: a range spanning more than MAX_COUNT+1 code points must still be split across
    // multiple physical entries (the packed key format's 2048-code-point-per-entry cap).
    int chunkCount = (max - min + MAX_COUNT) / (MAX_COUNT + 1); // ceil((max - min) / 2048)
    ensureCapacity(size + chunkCount); // one allocation for the whole call, not one per chunk.
    for (int chunkMin = min; chunkMin < max; chunkMin += MAX_COUNT + 1) {
      int chunkMax = Math.min(max, chunkMin + MAX_COUNT + 1);
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
   * MutableCodePointMap#putAll}. Delegates to the {@link #putAll(ArrayCodePointMap)} overload
   * below when {@code other} is one (the common case -- every real caller in this codebase merges
   * one {@code ArrayCodePointMap} into another), which has its own empty-target fast path and
   * avoids the {@code other.entrySet().size()} capacity probe below (expensive for an else-valued
   * source, which would force a full materializing walk just to count). Otherwise falls back to
   * the same sorted-sweep merge, just driven by {@link #forEachRange} instead of {@code
   * entrySet()} -- see {@link #sweepMerge} for the shared algorithm.
   */
  @Override
  public void putAll(CodePointMap<V> other) {
    if (other instanceof ArrayCodePointMap) {
      putAll((ArrayCodePointMap<V>) other);
      return;
    }
    if (other.isEmpty()) {
      return;
    }
    // No cheap upper bound available for an arbitrary CodePointMap source (entrySet().size() would
    // force a full materialize on an else-valued one) -- ensureCapacity's own incremental growth
    // absorbs it instead. Rare in practice: every CodePointMap implementation besides this one
    // (TreeCodePointMap) exists only as this class's differential-test oracle, never a production
    // putAll source.
    sweepMerge(other::forEachRange);
  }

  /**
   * {@link #putAll(CodePointMap)}, specialized to an {@code ArrayCodePointMap} source: the shape
   * of every real merge in this codebase (e.g. {@code PatternParser} building up a bracket
   * expression's ranges one member at a time).
   */
  public void putAll(ArrayCodePointMap<V> other) {
    if (other.size == 0 && other.elseValue == null) {
      return;
    }
    if (size == 0 && elseValue == null) {
      // Fast path: this map has nothing of its own yet, so putAll degenerates to becoming a copy
      // of `other` -- a straight array copy (the same trick the copy constructor and complement()
      // use), not the general sorted-merge sweep below, which would do the same work through a
      // slower path for no reason when there's nothing of this map's own data to merge around.
      // This is the common shape for "build a fresh accumulator/result map from one source" --
      // e.g. ComplexCharacter parsing's very first member, or union()'s own copy constructor call.
      keys = Arrays.copyOf(other.keys, other.size);
      values = Arrays.copyOf(other.values, other.size);
      size = other.size;
      elseValue = other.elseValue;
      return;
    }
    sweepMerge(other::forEachRange, other.size + 1); // +1: elseValue can add one gap-fill range
  }

  private void sweepMerge(Consumer<RangeConsumer<V>> otherRanges) {
    sweepMerge(otherRanges, -1);
  }

  /**
   * The sorted-sweep merge both {@code putAll} overloads share: a single pass over this map's own
   * entries (already sorted) and {@code otherRanges} (sorted per {@link CodePointMap}'s ordering
   * contract, invoked via {@link #forEachRange} rather than {@code entrySet()} -- no {@code Range}/
   * {@code Entry}/{@code Iterator} allocated per range for an {@code ArrayCodePointMap} source)
   * instead of one {@link #put} call per source range: each {@code other} range is appended as-is,
   * and whatever of this map's own entries falls outside every {@code other} range -- the parts
   * {@code other} doesn't overwrite -- is appended around it. This is O(this map's size + other's
   * size); when this map starts empty, the sweep degenerates to appending every {@code other}
   * range directly.
   *
   * <p>The sweep's cross-range state (which of this map's original entries is still pending, and
   * how much of it) has to live in a field of a small holder object, not local variables, since
   * {@code otherRanges}'s callback -- effectively a nested loop body -- can't reassign locals of
   * the enclosing method the way a plain {@code for} loop's body could.
   */
  private void sweepMerge(Consumer<RangeConsumer<V>> otherRanges, int otherSizeHint) {
    int[] oldKeys = keys;
    V[] oldValues = values;
    int oldSize = size;
    keys = new int[INITIAL_CAPACITY];
    values = newValuesArray(INITIAL_CAPACITY);
    size = 0;
    if (otherSizeHint >= 0) {
      ensureCapacity(oldSize + otherSizeHint); // upper bound on the merged result's size
    }

    SweepState<V> s = new SweepState<>();
    otherRanges.accept((oMin, oMax, oValue) -> {
      // Emit whatever of this map's original data lies entirely before oMin.
      while (true) {
        if (!s.pending) {
          if (s.i >= oldSize) {
            break;
          }
          s.pendingMin = keyMin(oldKeys[s.i]);
          s.pendingMax = keyMax(oldKeys[s.i]);
          s.pendingValue = oldValues[s.i];
          s.pending = true;
          s.i++;
        }
        if (s.pendingMin >= oMin) {
          break; // this pending entry starts at/after oMin -- nothing left to emit before it
        }
        int emitMax = Math.min(s.pendingMax, oMin);
        appendSorted(s.pendingMin, emitMax, s.pendingValue);
        if (emitMax >= s.pendingMax) {
          s.pending = false;
        } else {
          s.pendingMin = emitMax; // the rest overlaps `other`; handled by the loop below
        }
      }
      appendSorted(oMin, oMax, oValue);
      // Discard whatever of this map's original data `other`'s range just overwrote.
      while (true) {
        if (!s.pending) {
          if (s.i >= oldSize) {
            break;
          }
          s.pendingMin = keyMin(oldKeys[s.i]);
          s.pendingMax = keyMax(oldKeys[s.i]);
          s.pendingValue = oldValues[s.i];
          s.pending = true;
          s.i++;
        }
        if (s.pendingMin >= oMax) {
          break; // doesn't overlap this `other` range -- leave it for a later one, or the tail
        }
        if (s.pendingMax <= oMax) {
          s.pending = false; // fully overwritten
        } else {
          s.pendingMin = oMax; // partially overwritten; the remainder starts right after `other`
          break;
        }
      }
    });
    // Emit whatever of this map's original data is left after the last `other` range.
    if (s.pending) {
      appendSorted(s.pendingMin, s.pendingMax, s.pendingValue);
    }
    while (s.i < oldSize) {
      appendSorted(keyMin(oldKeys[s.i]), keyMax(oldKeys[s.i]), oldValues[s.i]);
      s.i++;
    }
  }

  /** Mutable cross-callback state for {@link #sweepMerge} -- see its own doc for why this exists. */
  private static final class SweepState<V> {
    int i;
    boolean pending;
    int pendingMin;
    int pendingMax;
    @Nullable V pendingValue;
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
    // Built directly via forEachRange, not entrySet() -- no Range/Entry/Iterator allocated per
    // range just to immediately stringify it.
    StringBuilder sb = new StringBuilder("[");
    boolean[] needsComma = {false};
    forEachRange((min, max, value) -> {
      if (needsComma[0]) {
        sb.append(", ");
      }
      needsComma[0] = true;
      sb.append(new Range(min, max)).append('=').append(value);
    });
    return sb.append(']').toString();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (other instanceof ArrayCodePointMap) {
      // Direct raw-array comparison, no entrySet() (and so no Range/Entry/Iterator) at all: every
      // mutator here keeps `keys`/`values` in canonical coalesced form (see the class doc), so two
      // equal maps' arrays match position-for-position up to `size`, regardless of elseValue.
      ArrayCodePointMap<?> o = (ArrayCodePointMap<?>) other;
      if (size != o.size || !Objects.equals(elseValue, o.elseValue)) {
        return false;
      }
      for (int i = 0; i < size; i++) {
        if (keys[i] != o.keys[i] || !Objects.equals(values[i], o.values[i])) {
          return false;
        }
      }
      return true;
    }
    if (!(other instanceof CodePointMap)) {
      return false;
    }
    // Cross-implementation fallback: a real Set comparison, not a lockstep range-by-range walk --
    // this class coalesces adjacent equal-value ranges (see the class doc) but e.g. TreeCodePointMap
    // never does, so an equal-content map on the other side can legitimately split the same logical
    // mapping across more, differently-bounded ranges. Built directly via forEachRange on both
    // sides rather than entrySet(), even though the result is the same set entrySet() would have
    // built -- this rare (differential-test-only in practice) path is the only place this class
    // still needs a real Entry/Range per range at all.
    Set<Entry<Range, V>> mine = new LinkedHashSet<>();
    forEachRange((min, max, value) -> mine.add(new ImmutableEntry<>(new Range(min, max), value)));
    Set<Entry<Range, ?>> theirs = new LinkedHashSet<>();
    ((CodePointMap<?>) other).forEachRange((min, max, value) -> theirs.add(new ImmutableEntry<>(new Range(min, max), value)));
    return mine.equals(theirs);
  }

  @Override
  public int hashCode() {
    // Set<Entry>.hashCode() is defined as the sum of each entry's own hashCode (key ^ value, per
    // Map.Entry's contract) -- order-independent, so this matches what entrySet().hashCode() would
    // produce exactly, without actually materializing a Range/Entry per range to get there.
    int[] hash = {0};
    forEachRange((min, max, value) -> hash[0] += new Range(min, max).hashCode() ^ (value == null ? 0 : value.hashCode()));
    return hash[0];
  }
}
