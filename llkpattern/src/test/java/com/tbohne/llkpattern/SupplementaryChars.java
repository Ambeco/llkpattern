package com.tbohne.llkpattern;

/**
 * Shared supplementary (astral) code point constants for the per-file test-pattern migration
 * sweep -- see remaining_work.md's "In progress" entry and SupplementaryPatternTextTest for why:
 * each replaces a plain ASCII letter a test used to use, so a test still reads the same shape it
 * always did while actually exercising a multi-code-unit character.
 *
 * <p>{@code A} through {@code Z} are U+10000 through U+10019 (LINEAR B SYLLABLE B008 A onward), a
 * contiguous run standing in for a-z. {@code OUTSIDE} (U+10400, DESERET CAPITAL LETTER LONG A) is
 * a code point outside that run entirely -- the role 'A'/an uppercase letter usually played as
 * "something [a-z] and its friends must NOT match".
 *
 * <p>Not used by the earlier files in this sweep (PatternParserTest, GroupSyntaxTest,
 * CharacterClassTest, QuantifierModifierTest), which predate this shared class and each define
 * their own equivalent constants inline -- consistent with this codebase's general precedent of
 * accepting small duplication across independent test files rather than introducing a shared
 * dependency after the fact once each already reads on its own; new files in the sweep use this
 * one instead of re-deriving it themselves.
 */
final class SupplementaryChars {
  private SupplementaryChars() {}

  static String letter(int offset) {
    return "\uD800" + (char) (0xDC00 + offset);
  }

  static final String A = letter(0);
  static final String B = letter(1);
  static final String C = letter(2);
  static final String D = letter(3);
  static final String E = letter(4);
  static final String F = letter(5);
  static final String G = letter(6);
  static final String H = letter(7);
  static final String I = letter(8);
  static final String J = letter(9);
  static final String K = letter(10);
  static final String L = letter(11);
  static final String M = letter(12);
  static final String N = letter(13);
  static final String O = letter(14);
  static final String P = letter(15);
  static final String Q = letter(16);
  static final String R = letter(17);
  static final String S = letter(18);
  static final String T = letter(19);
  static final String U = letter(20);
  static final String V = letter(21);
  static final String W = letter(22);
  static final String X = letter(23);
  static final String Y = letter(24);
  static final String Z = letter(25);
  // U+10400 (DESERET CAPITAL LETTER LONG A) -- outside the A-Z (U+10000-U+10019) run above.
  static final String OUTSIDE = "𐐀";

  /** {@code s} repeated {@code n} times -- not {@code String.repeat()}, which needs Java 11+. */
  static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
