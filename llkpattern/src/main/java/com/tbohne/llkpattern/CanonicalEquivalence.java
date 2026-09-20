package com.tbohne.llkpattern;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code CANON_EQ} pre-pass: rewrites pattern text so every base-plus-combining-marks cluster
 * becomes a group matching each canonically equivalent spelling of it (precomposed forms, and
 * mark permutations that canonical ordering treats as the same), like {@code java.util.regex}.
 * The input is never normalized, so offsets stay in input coordinates.
 *
 * <p>The equivalent spellings of one cluster are left-factored into a trie before being emitted
 * (a base with two reorderable marks becomes one group whose first level is the base, then one
 * nested group for the two mark orders, next to each precomposed form) instead of a flat list,
 * because a flat list has several branches starting with the same character, which is exactly
 * the ambiguity this engine rejects. The spellings of one cluster are
 * never prefixes of each other, so the trie always has disjoint first characters at every level.
 *
 * <p>Runs on the raw text, before escapes are decoded, so a character written as a
 * unicode escape in the pattern is not affected -- same as {@code java.util.regex}.
 */
final class CanonicalEquivalence {
  private CanonicalEquivalence() {}

  // More marks than this makes the permutation count (k!) impractical.
  private static final int MAX_MARKS = 6;

  // A cluster's starter can't be one of these; they keep their regex meaning.
  private static final String NON_STARTERS = "\\()[]{}|.^$?+*";

  static String rewrite(String pattern, boolean unicodeFold) {
    String nfd = Normalizer.normalize(pattern, Normalizer.Form.NFD);
    Map<String, Set<Integer>> originals = originalSpellings(pattern);
    int n = nfd.length();
    StringBuilder out = new StringBuilder(n + 16);
    int i = 0;
    while (i < n) {
      int c = nfd.codePointAt(i);
      int next = i + Character.charCount(c);
      if (c == '\\' && next < n) {
        int escaped = nfd.codePointAt(next);
        out.appendCodePoint(c).appendCodePoint(escaped);
        i = next + Character.charCount(escaped);
      } else if (c == '[') {
        i = rewriteClass(nfd, i, out, originals, unicodeFold);
      } else if (isStarter(c) && next < n && isCombining(nfd.codePointAt(next))) {
        int end = clusterEnd(nfd, next);
        Node root = new Node();
        addEquivalents(root, nfd, i, end, pattern, originals, unicodeFold);
        out.append("(?:");
        appendAlternatives(root, out);
        out.append(')');
        i = end;
      } else if (isCombining(c) && originals.containsKey(nfd.substring(i, clusterEnd(nfd, i)))) {
        // Marks with no base of their own (e.g. U+0344): only the original spelling is added.
        int end = clusterEnd(nfd, i);
        Node root = new Node();
        root.add(nfd.substring(i, end));
        addOriginals(root, nfd.substring(i, end), originals, unicodeFold);
        out.append("(?:");
        appendAlternatives(root, out);
        out.append(')');
        i = end;
      } else {
        out.appendCodePoint(c);
        i = next;
      }
    }
    return out.toString();
  }

  /**
   * Rewrites the bracket expression starting at {@code start} (which is {@code '['}), appending to
   * {@code out} and returning the index just past its closing {@code ']'}. Clusters can't live
   * inside a class, so they're pulled out into alternatives of an enclosing group:
   * a class holding x and a precomposed e-acute becomes an enclosing group with the class
   * of the plain members as one alternative and the cluster spellings as the others.
   */
  private static int rewriteClass(
      String nfd, int start, StringBuilder out, Map<String, Set<Integer>> originals, boolean unicodeFold) {
    int n = nfd.length();
    StringBuilder plain = new StringBuilder();
    Node clusters = null;
    int i = start + 1;
    if (i < n && nfd.charAt(i) == '^') {
      plain.append('^');
      i++;
    }
    int depth = 1;
    while (i < n) {
      int c = nfd.codePointAt(i);
      int next = i + Character.charCount(c);
      if (c == '\\' && next < n) {
        int escaped = nfd.codePointAt(next);
        plain.appendCodePoint(c).appendCodePoint(escaped);
        i = next + Character.charCount(escaped);
        continue;
      }
      if (c == '[') {
        depth++;
      } else if (c == ']') {
        depth--;
        if (depth == 0) {
          i = next;
          break;
        }
      } else if (isStarter(c) && next < n && isCombining(nfd.codePointAt(next))) {
        boolean negated = plain.length() > 0 && plain.charAt(0) == '^';
        if (negated || depth > 1 || rangeNeighbour(nfd, start, i, clusterEnd(nfd, next))) {
          throw new PatternSyntaxException(
              "CANON_EQ: a combining sequence (or precomposed character) inside a "
                  + (negated ? "negated " : depth > 1 ? "nested " : "range-bounding ")
                  + "character class can't be matched by canonical equivalence here, since the "
                  + "class would have to match a multi-code-point sequence; move it out of the "
                  + "class, e.g. write (?:[^...]|X) explicitly, or drop CANON_EQ",
              nfd, i);
        }
        int end = clusterEnd(nfd, next);
        if (clusters == null) {
          clusters = new Node();
        }
        addEquivalents(clusters, nfd, i, end, nfd, originals, unicodeFold);
        i = end;
        continue;
      }
      plain.appendCodePoint(c);
      i = next;
    }
    if (depth != 0) {
      // Unclosed: emit as-is so the parser reports it against the text it was given.
      out.append('[').append(plain);
      return n;
    }
    if (clusters == null) {
      out.append('[').append(plain).append(']');
    } else {
      out.append("(?:");
      boolean hasPlain = plain.length() > 0;
      if (hasPlain) {
        out.append('[').append(plain).append("]|");
      }
      appendAlternatives(clusters, out);
      out.append(')');
    }
    return i;
  }

  // Whether the cluster spanning [from, to) sits next to a '-' making it a range endpoint.
  private static boolean rangeNeighbour(String nfd, int classStart, int from, int to) {
    boolean before = from - 1 > classStart && nfd.charAt(from - 1) == '-';
    boolean after = to < nfd.length() && nfd.charAt(to) == '-';
    return before || after;
  }

  private static boolean isStarter(int c) {
    return c >= 0x80 || NON_STARTERS.indexOf(c) < 0;
  }

  // Only non-spacing marks and Hangul vowel/trailing jamo, matching what java.util.regex groups
  // with the preceding character.
  private static boolean isCombining(int c) {
    return Character.getType(c) == Character.NON_SPACING_MARK
        || (c >= 0x1161 && c <= 0x11FF)
        || (c >= 0xD7B0 && c <= 0xD7FF);
  }

  private static int clusterEnd(String nfd, int from) {
    int j = from;
    while (j < nfd.length() && isCombining(nfd.codePointAt(j))) {
      j += Character.charCount(nfd.codePointAt(j));
    }
    return j;
  }

  /** Adds every canonically equivalent spelling of nfd[from, to) to {@code root}. */
  private static void addEquivalents(
      Node root, String nfd, int from, int to, String original, Map<String, Set<Integer>> originals,
      boolean unicodeFold) {
    int starter = nfd.codePointAt(from);
    List<Integer> marks = new ArrayList<>();
    for (int j = from + Character.charCount(starter); j < to; j += Character.charCount(nfd.codePointAt(j))) {
      marks.add(nfd.codePointAt(j));
    }
    if (marks.size() > MAX_MARKS) {
      throw new PatternSyntaxException(
          "CANON_EQ: " + marks.size() + " consecutive combining marks after one base character is "
              + "more than the supported maximum of " + MAX_MARKS + ", since every ordering of them "
              + "would have to be considered",
          original, 0);
    }
    String cluster = nfd.substring(from, to);
    Set<String> spellings = new LinkedHashSet<>();
    List<List<Integer>> orders = new ArrayList<>();
    permute(marks, 0, orders);
    for (List<Integer> order : orders) {
      if (!Normalizer.normalize(build(starter, order, order.size()), Normalizer.Form.NFD).equals(cluster)) {
        continue; // canonical ordering keeps these two marks in this order
      }
      spellings.add(build(starter, order, order.size()));
      for (int j = 1; j <= order.size(); j++) {
        String composed = Normalizer.normalize(build(starter, order, j), Normalizer.Form.NFC);
        if (composed.codePointCount(0, composed.length()) == 1) {
          StringBuilder sb = new StringBuilder(composed);
          for (int k = j; k < order.size(); k++) {
            sb.appendCodePoint(order.get(k));
          }
          spellings.add(sb.toString());
        }
      }
    }
    for (String s : spellings) {
      root.add(s);
    }
    addOriginals(root, cluster, originals, unicodeFold);
  }

  /**
   * Code points written in the pattern whose decomposition is exactly {@code nfdText}. Composing
   * never yields them (singletons like U+212B, composition exclusions like U+0958), so a cluster
   * would otherwise stop matching the very character the pattern was written with.
   */
  private static void addOriginals(
      Node root, String nfdText, Map<String, Set<Integer>> originals, boolean unicodeFold) {
    Set<Integer> singles = originals.get(nfdText);
    if (singles != null) {
      for (int c : singles) {
        // Under UNICODE_CASE, U+212B and U+00C5 fold together, so both as branches would be
        // an ambiguity between two spellings that already match the same input.
        if (!unicodeFold || !root.hasSingleFoldingTo(fold(c))) {
          root.add(new String(Character.toChars(c)));
        }
      }
    }
  }

  private static Map<String, Set<Integer>> originalSpellings(String pattern) {
    Map<String, Set<Integer>> result = new LinkedHashMap<>();
    for (int i = 0; i < pattern.length(); i += Character.charCount(pattern.codePointAt(i))) {
      int c = pattern.codePointAt(i);
      if (c < 0xC0) {
        continue; // nothing below U+00C0 decomposes
      }
      String s = new String(Character.toChars(c));
      String d = Normalizer.normalize(s, Normalizer.Form.NFD);
      if (!d.equals(s)) {
        Set<Integer> set = result.get(d);
        if (set == null) {
          set = new LinkedHashSet<>();
          result.put(d, set);
        }
        set.add(c);
      }
    }
    return result;
  }

  private static String build(int starter, List<Integer> marks, int markCount) {
    StringBuilder sb = new StringBuilder().appendCodePoint(starter);
    for (int k = 0; k < markCount; k++) {
      sb.appendCodePoint(marks.get(k));
    }
    return sb.toString();
  }

  private static void permute(List<Integer> items, int k, List<List<Integer>> out) {
    if (k == items.size()) {
      out.add(new ArrayList<>(items));
      return;
    }
    for (int i = k; i < items.size(); i++) {
      swap(items, k, i);
      permute(items, k + 1, out);
      swap(items, k, i);
    }
  }

  private static void swap(List<Integer> list, int a, int b) {
    Integer t = list.get(a);
    list.set(a, list.get(b));
    list.set(b, t);
  }

  /** A trie of code points; a node with no children ends a spelling. */
  private static final class Node {
    final Map<Integer, Node> children = new LinkedHashMap<>();

    boolean hasSingleFoldingTo(int folded) {
      for (Map.Entry<Integer, Node> e : children.entrySet()) {
        if (e.getValue().children.isEmpty() && fold(e.getKey()) == folded) {
          return true;
        }
      }
      return false;
    }

    void add(String s) {
      Node node = this;
      for (int i = 0; i < s.length(); i += Character.charCount(s.codePointAt(i))) {
        int c = s.codePointAt(i);
        Node child = node.children.get(c);
        if (child == null) {
          child = new Node();
          node.children.put(c, child);
        }
        node = child;
      }
    }
  }

  // "a|b|..." over the node's children, each followed by its own factored remainder.
  private static void appendAlternatives(Node node, StringBuilder out) {
    boolean first = true;
    for (Map.Entry<Integer, Node> e : node.children.entrySet()) {
      if (!first) {
        out.append('|');
      }
      first = false;
      appendLiteral(e.getKey(), out);
      appendRemainder(e.getValue(), out);
    }
  }

  private static void appendRemainder(Node node, StringBuilder out) {
    if (node.children.isEmpty()) {
      return;
    }
    if (node.children.size() == 1) {
      appendAlternatives(node, out);
    } else {
      out.append("(?:");
      appendAlternatives(node, out);
      out.append(')');
    }
  }

  private static int fold(int c) {
    return Character.toLowerCase(Character.toUpperCase(c));
  }

  // ASCII non-alphanumerics get a backslash, as removeQuoting does, so a literal can't be read as
  // syntax (or as COMMENTS-mode whitespace).
  private static void appendLiteral(int c, StringBuilder out) {
    if (c < 128 && !Character.isLetterOrDigit(c)) {
      out.append('\\');
    }
    out.appendCodePoint(c);
  }
}
