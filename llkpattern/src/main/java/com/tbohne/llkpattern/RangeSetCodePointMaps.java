package com.tbohne.llkpattern;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;

/**
 * One-time adapter from a Guava {@code RangeSet<Integer>} to a {@link CodePointMap}, used only at
 * the point a {@link NamedCharClass}/{@link NamedCharClass.RegexCharacterClass} character class is
 * actually consumed while parsing a pattern (compile time, once per named-class occurrence in the
 * source pattern text -- never on the match-time hot path). {@code NamedCharClass}/
 * {@code UnicodePredicates} themselves are deliberately left on Guava (see remaining_work.md):
 * their own {@code ImmutableRangeSet} constants and the {@code unicodeanalyzer}-generated data they
 * come from are unaffected by this class, which only converts a result already computed by them.
 *
 * <p>Every range is clamped to the code point domain {@code [0, MAX_CODE_POINT]} and eagerly
 * materialized into explicit entries here, rather than preserved as a {@link CodePointMap} else-
 * value the way {@link CodePointMap#complement} does -- unlike {@code complement}, which exists
 * for match-time-relevant maps built directly out of other {@code CodePointMap}s, this is a
 * one-time conversion of already-fully-known (if sometimes Guava-unbounded, e.g. a negated
 * built-in like {@code \D}) data, where eagerly clamping is simplest and cheap enough for a
 * per-pattern-compile cost.
 */
final class RangeSetCodePointMaps {
  private RangeSetCodePointMaps() {}

  static MutableCodePointMap<Boolean> toCodePointMap(RangeSet<Integer> ranges) {
    MutableCodePointMap<Boolean> result = new ArrayCodePointMap<>();
    for (Range<Integer> range : ranges.asRanges()) {
      int min =
          range.hasLowerBound()
              ? (range.lowerBoundType() == BoundType.CLOSED ? range.lowerEndpoint() : range.lowerEndpoint() + 1)
              : 0;
      int max =
          range.hasUpperBound()
              ? (range.upperBoundType() == BoundType.CLOSED ? range.upperEndpoint() + 1 : range.upperEndpoint())
              : CodePointMap.MAX_CODE_POINT + 1;
      min = Math.max(min, 0);
      max = Math.min(max, CodePointMap.MAX_CODE_POINT + 1);
      if (min < max) {
        result.put(min, max, Boolean.TRUE);
      }
    }
    return result;
  }
}
