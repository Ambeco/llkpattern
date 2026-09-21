package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointSet.MutableCodePointSet;
import java.util.Arrays;

/**
 * The case-insensitive member sets {@code java.util.regex} builds under {@code CASE_INSENSITIVE},
 * computed once at parse time (see {@code PatternParser}) so a character class needs no folding at
 * match time.
 *
 * <p>Without {@code UNICODE_CASE} only ASCII letters fold. With it, the JDK's rules are:
 *
 * <ul>
 *   <li>a single character {@code c} (a literal, or one member of a bracket expression) matches
 *       every {@code x} with {@code key(x) == key(c)}, plus {@code key(c)} itself, where {@code
 *       key(x) = lower(upper(x))} -- {@link #addSingle};
 *   <li>a range {@code [lo-hi]} matches {@code x} when {@code x}, {@code upper(x)} or {@code
 *       key(x)} lies in it -- {@link #addRange}.
 * </ul>
 *
 * Both need the preimage of a set under {@code upper}/{@code key}, not its image: {@code (?iu)[s]}
 * must match {@code ſ} (U+017F), which no forward {@code upper}/{@code lower} of {@code s} reaches.
 * So the two tables below list, for every character that folds to something else, the pair
 * (value it folds to, the character), sorted by value, and a lookup is a binary search plus the hits.
 *
 * <p>Named classes ({@code \w}, {@code \p{InGreek}}, ...) are never folded by the JDK, only
 * substituted (see {@code NamedCharClass#caseInsensitive}), so they never come through here.
 * JDK 27 additionally "closes" a range under simple case folding (see {@link #addClosing}); that is
 * reproduced only when the running platform's own {@code java.util.regex} does it (see {@link
 * #HOST_CLOSES_RANGES}), so this class always mirrors the host.
 */
final class CaseFolding {
  private CaseFolding() {}

  // Cased characters all lie below this (the highest is Adlam, U+1E943).
  private static final int CASED_LIMIT = 0x20000;
  // Below this many members, expand() walks the members instead of the whole table.
  private static final int SMALL_SET_MEMBERS = 64;
  private static final int VALUE_SHIFT = 21;
  private static final long CP_MASK = (1L << VALUE_SHIFT) - 1;

  private static final class Tables {
    /** (key(x) << 21 | x), for every x with key(x) != x. */
    final long[] keyPairs;
    /** (upper(x) << 21 | x), for every x with upper(x) != x. */
    final long[] upperPairs;

    Tables() {
      long[] key = new long[4096];
      long[] upper = new long[4096];
      int keyCount = 0;
      int upperCount = 0;
      for (int x = 0; x < CASED_LIMIT; x++) {
        int u = Character.toUpperCase(x);
        int k = Character.toLowerCase(u);
        if (k != x) {
          if (keyCount == key.length) {
            key = Arrays.copyOf(key, keyCount * 2);
          }
          key[keyCount++] = ((long) k << VALUE_SHIFT) | x;
        }
        if (u != x) {
          if (upperCount == upper.length) {
            upper = Arrays.copyOf(upper, upperCount * 2);
          }
          upper[upperCount++] = ((long) u << VALUE_SHIFT) | x;
        }
      }
      keyPairs = Arrays.copyOf(key, keyCount);
      upperPairs = Arrays.copyOf(upper, upperCount);
      Arrays.sort(keyPairs);
      Arrays.sort(upperPairs);
    }
  }

  /**
   * JDK 27's {@code Pattern.CIRangeU} additions: (character with a non-round-trip simple case
   * folding, what it folds to). Copied from {@code jdk.internal.lang.CaseFolding}'s
   * {@code expanded_case_map}, itself derived from CaseFolding.txt.
   */
  private static final int[] CLOSING_PAIRS = {
    0x0131, 0x0049, 0x00B5, 0x03BC, 0x0130, 0x0069, 0x017F, 0x0073, 0x01C5, 0x01C6, 0x01C8, 0x01C9,
    0x01CB, 0x01CC, 0x01F2, 0x01F3, 0x0345, 0x03B9, 0x03C2, 0x03C3, 0x03D0, 0x03B2, 0x03D1, 0x03B8,
    0x03D5, 0x03C6, 0x03D6, 0x03C0, 0x03F0, 0x03BA, 0x03F1, 0x03C1, 0x03F4, 0x03B8, 0x03F5, 0x03B5,
    0x1C80, 0x0432, 0x1C81, 0x0434, 0x1C82, 0x043E, 0x1C83, 0x0441, 0x1C84, 0x0442, 0x1C85, 0x0442,
    0x1C86, 0x044A, 0x1C87, 0x0463, 0x1C88, 0xA64B, 0x1E9B, 0x1E61, 0x1E9E, 0x00DF, 0x1FBE, 0x03B9,
    0x1FD3, 0x0390, 0x1FE3, 0x03B0, 0x2126, 0x03C9, 0x212A, 0x006B, 0x212B, 0x00E5, 0xFB05, 0xFB06,
  };

  /** True if the running {@code java.util.regex} closes {@code (?iu)[lo-hi]} ranges (JDK 27+). */
  private static final boolean HOST_CLOSES_RANGES = probeHostClosesRanges();

  private static boolean probeHostClosesRanges() {
    try {
      // U+017F folds to 's'; only a closing range lets [U+017F-U+0180] match 's'.
      return java.util.regex.Pattern.compile("(?iu)[\\u017f-\\u0180]").matcher("s").matches();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static final class Holder {
    static final Tables TABLES = new Tables();
  }

  /** True if {@code flags} asks for Unicode (rather than ASCII-only) case folding. */
  static boolean isUnicodeCase(int flags) {
    return (flags & (Ll1Pattern.UNICODE_CASE | Ll1Pattern.UNICODE_CHARACTER_CLASS)) != 0;
  }

  /** Adds to {@code b} everything a lone {@code codePoint} matches under {@code CASE_INSENSITIVE}. */
  static void addSingle(CodePointSetBuilder b, int codePoint, boolean unicode) {
    b.add(codePoint, codePoint + 1);
    if (!unicode) {
      if (codePoint >= 'a' && codePoint <= 'z') {
        b.add(codePoint - 32);
      } else if (codePoint >= 'A' && codePoint <= 'Z') {
        b.add(codePoint + 32);
      }
      return;
    }
    if (codePoint >= CASED_LIMIT) {
      return;
    }
    int key = Character.toLowerCase(Character.toUpperCase(codePoint));
    b.add(key);
    forEachWithValue(Holder.TABLES.keyPairs, key, key, x -> b.add(x));
  }

  /** Adds {@code [min, max)} to {@code b}, plus what a {@code [lo-hi]} range matches beyond it. */
  static void addRange(CodePointSetBuilder b, int min, int max, boolean unicode) {
    b.add(min, max);
    if (!unicode) {
      addAsciiShift(b, min, max, 'A', 'Z', 32);
      addAsciiShift(b, min, max, 'a', 'z', -32);
      return;
    }
    Tables t = Holder.TABLES;
    forEachWithValue(t.keyPairs, min, max - 1, x -> b.add(x));
    forEachWithValue(t.upperPairs, min, max - 1, x -> b.add(x));
    if (HOST_CLOSES_RANGES) {
      addClosing(b, min, max, t);
    }
  }

  /**
   * JDK 27's range closure: for each pair (c, f) with c in {@code [min, max)} and f outside it, the
   * range also matches every {@code x} whose {@code upper(x)} or {@code key(x)} equals f.
   */
  private static void addClosing(CodePointSetBuilder b, int min, int max, Tables t) {
    for (int i = 0; i < CLOSING_PAIRS.length; i += 2) {
      int c = CLOSING_PAIRS[i];
      int f = CLOSING_PAIRS[i + 1];
      if (c < min || c >= max || (f >= min && f < max)) {
        continue;
      }
      // x == f itself matches when upper(f) == f or key(f) == f.
      if (Character.toUpperCase(f) == f || Character.toLowerCase(Character.toUpperCase(f)) == f) {
        b.add(f);
      }
      forEachWithValue(t.keyPairs, f, f, x -> b.add(x));
      forEachWithValue(t.upperPairs, f, f, x -> b.add(x));
    }
  }

  /**
   * {@code exact}, plus every character sharing a case-equivalence class with one of its members:
   * a superset of what any single class or range built from {@code exact} matches. Used for
   * dispatch gates and the ambiguity check, where being a little too wide is safe (see {@code
   * PatternConstruct#checkDisjoint}) but being too narrow would skip a matching candidate.
   * Returns {@code exact} itself when nothing is added.
   */
  static CodePointSet expand(CodePointSet exact, boolean unicode) {
    CodePointSetBuilder additions = CodePointSetBuilder.create();
    boolean[] any = {false};
    if (!unicode) {
      exact.forEachRange((min, max) -> {
        int upperLo = Math.max(min, 'A');
        int upperHi = Math.min(max, 'Z' + 1);
        if (upperLo < upperHi) {
          additions.add(upperLo + 32, upperHi + 32);
          any[0] = true;
        }
        int lowerLo = Math.max(min, 'a');
        int lowerHi = Math.min(max, 'z' + 1);
        if (lowerLo < lowerHi) {
          additions.add(lowerLo - 32, lowerHi - 32);
          any[0] = true;
        }
      });
    } else {
      // A small set costs less to expand member by member than to scan the whole table.
      int[] budget = {SMALL_SET_MEMBERS};
      exact.forEachRange((min, max) -> budget[0] -= max - min);
      if (budget[0] >= 0) {
        exact.forEachRange((min, max) -> {
          for (int cp = min; cp < max; cp++) {
            addSingle(additions, cp, true);
          }
        });
        any[0] = true;
      } else {
        for (long pair : Holder.TABLES.keyPairs) {
          int key = (int) (pair >>> VALUE_SHIFT);
          int x = (int) (pair & CP_MASK);
          if (exact.contains(x) || exact.contains(key)) {
            additions.add(x);
            additions.add(key);
            any[0] = true;
          }
        }
      }
    }
    if (!any[0]) {
      return exact;
    }
    MutableCodePointSet result = new ArrayCodePointSet();
    result.addAll(exact);
    result.addAll(additions.build());
    return result;
  }

  private static void addAsciiShift(
      CodePointSetBuilder b, int min, int max, int from, int to, int delta) {
    int lo = Math.max(min, from);
    int hi = Math.min(max, to + 1);
    if (lo < hi) {
      b.add(lo + delta, hi + delta);
    }
  }

  private static void forEachWithValue(long[] pairs, int minValue, int maxValue, java.util.function.IntConsumer action) {
    int i = Arrays.binarySearch(pairs, (long) minValue << VALUE_SHIFT);
    if (i < 0) {
      i = -i - 1;
    }
    long limit = ((long) maxValue + 1) << VALUE_SHIFT;
    for (; i < pairs.length && pairs[i] < limit; i++) {
      action.accept((int) (pairs[i] & CP_MASK));
    }
  }
}
