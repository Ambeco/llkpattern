package com.tbohne.llkpattern.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Classifies a golden-corpus row into one mutually-exclusive regex-feature bucket, from its pattern
 * text and flags alone. Used by the paired benchmark harnesses ({@link PairedBench}) so each
 * feature's llk/regex ratio is measured on its own: a regression in a rare feature is a rounding
 * error in the whole-corpus ratio but obvious in its own bucket. Shared source -- compiled into both
 * the desktop {@code jmh} runner and the Android instrumentation test (see {@code shared-bench/}),
 * so it must stay plain Java 8 with no dependencies.
 *
 * <p>Buckets are checked in priority order (most specialised first), so a row lands in the first
 * bucket whose feature it uses.
 */
public final class RowBuckets {
  private RowBuckets() {}

  private static final Pattern LOOKAROUND = Pattern.compile("\\(\\?<?[=!]");
  private static final Pattern BACKREF = Pattern.compile("\\\\[1-9]|\\\\k<");
  private static final Pattern UNICODE_FEATURE =
      Pattern.compile("\\\\[pP]|\\\\X|\\\\b\\{g\\}|\\\\N\\{|\\\\R|\\\\h|\\\\v|\\(\\?U");
  private static final Pattern EMBEDDED_FLAGS = Pattern.compile("\\(\\?[a-zA-Z]*[imsuxdU]");
  private static final Pattern WORD_BOUNDARY = Pattern.compile("\\\\[bBG]");
  private static final Pattern ANCHOR = Pattern.compile("\\\\[AZz]|\\^|\\$");
  private static final Pattern QUANTIFIER = Pattern.compile("[*+?]|\\{\\d");

  /** Bucket for the rows too few to time on their own -- see {@code PairedBench}'s caller. */
  public static final String OTHER = "other";

  public static String bucketOf(String pattern, String flags) {
    if (LOOKAROUND.matcher(pattern).find()) {
      return "lookaround";
    }
    if (BACKREF.matcher(pattern).find()) {
      return "backref";
    }
    if (UNICODE_FEATURE.matcher(pattern).find()) {
      return "unicode-feature";
    }
    if (flags.contains("CASE_INSENSITIVE") || flags.contains("UNICODE_CASE")
        || EMBEDDED_FLAGS.matcher(pattern).find()) {
      return "flags";
    }
    if (!isAscii(pattern)) {
      return "non-ascii";
    }
    if (WORD_BOUNDARY.matcher(pattern).find()) {
      return "word-boundary";
    }
    if (pattern.indexOf('[') >= 0) {
      return "char-class";
    }
    if (pattern.indexOf('|') >= 0) {
      return "alternation";
    }
    if (pattern.indexOf('(') >= 0) {
      return "group";
    }
    if (QUANTIFIER.matcher(pattern).find()) {
      return "quantifier";
    }
    if (ANCHOR.matcher(pattern).find()) {
      return "anchor";
    }
    return "literal";
  }

  /** Bucket name -> row indices, with buckets under {@code minRows} merged into {@link #OTHER}
   *  (too small to time on their own). Sorted by name, so deterministic. */
  public static Map<String, List<Integer>> group(List<String> patterns, List<String> flags,
      int minRows) {
    Map<String, List<Integer>> raw = new TreeMap<>();
    for (int i = 0; i < patterns.size(); i++) {
      String name = bucketOf(patterns.get(i), flags.get(i));
      List<Integer> list = raw.get(name);
      if (list == null) {
        list = new ArrayList<>();
        raw.put(name, list);
      }
      list.add(i);
    }
    Map<String, List<Integer>> merged = new TreeMap<>();
    List<Integer> other = new ArrayList<>();
    for (Map.Entry<String, List<Integer>> e : raw.entrySet()) {
      if (e.getValue().size() < minRows) {
        other.addAll(e.getValue());
      } else {
        merged.put(e.getKey(), e.getValue());
      }
    }
    if (!other.isEmpty()) {
      merged.put(OTHER, other);
    }
    return merged;
  }

  private static boolean isAscii(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) > 0x7f) {
        return false;
      }
    }
    return true;
  }
}
