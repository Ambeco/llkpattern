package com.tbohne.llkpattern.impl.parser;

/** Stateless text and code point helpers shared by the parser classes. */
final class PatternText {
  private PatternText() {}

  static boolean isQuantifierChar(int c) {
    return c == '?' || c == '*' || c == '+' || c == '{';
  }

  static boolean isAsciiAlphanumeric(int c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /** The value of hex digit {@code c}, or -1 if it isn't one. */
  static int hexDigitValue(int c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    } else if (c >= 'a' && c <= 'f') {
      return 10 + c - 'a';
    } else if (c >= 'A' && c <= 'F') {
      return 10 + c - 'A';
    }
    return -1;
  }

  static boolean startsBracedQuantifier(char afterBrace) {
    return (afterBrace >= '0' && afterBrace <= '9') || afterBrace == ',';
  }

  /**
   * Rewrites every {@code \Q...\E} span into escaped literal characters, as {@code
   * java.util.regex} does, so the rest of the parser never sees quotation: ASCII non-alphanumerics
   * get a backslash, everything else is copied verbatim. An unterminated {@code \Q} quotes to the
   * end of the pattern. Error positions in a pattern containing quotation refer to the rewritten
   * text. Returns {@code pattern} itself when it contains no {@code \Q}.
   */
  static boolean hasQuoteStart(char[] chars) {
    for (int i = 0, last = chars.length - 1; i < last; i++) {
      if (chars[i] == '\\' && chars[i + 1] == 'Q') {
        return true;
      }
    }
    return false;
  }

  static String removeQuoting(String pattern) {
    if (pattern.indexOf("\\Q") < 0) {
      return pattern;
    }
    StringBuilder out = new StringBuilder(pattern.length() + 8);
    int i = 0;
    int n = pattern.length();
    while (i < n) {
      char c = pattern.charAt(i);
      if (c != '\\' || i + 1 >= n) {
        out.append(c);
        i++;
      } else if (pattern.charAt(i + 1) != 'Q') {
        out.append(c).append(pattern.charAt(i + 1));
        i += 2;
      } else {
        i += 2;
        while (i < n && !(pattern.charAt(i) == '\\' && i + 1 < n && pattern.charAt(i + 1) == 'E')) {
          char q = pattern.charAt(i++);
          if (q < 128 && !Character.isLetterOrDigit(q)) {
            out.append('\\');
          }
          out.append(q);
        }
        i += 2; // skip "\E" (or run past the end for an unterminated quote)
      }
    }
    return out.toString();
  }

  // Not sb.appendCodePoint: it allocates a throwaway char[2] (Character.toChars) for supplementary code points,
  // and this runs once per literal character while parsing (alloc sampling).
  static void appendCodePoint(StringBuilder sb, int codePoint) {
    if (Character.isBmpCodePoint(codePoint)) {
      sb.append((char) codePoint);
    } else {
      sb.append(Character.highSurrogate(codePoint)).append(Character.lowSurrogate(codePoint));
    }
  }

  static Object[] concatObjectArrays(Object[] array1, Object[] array2) {
    Object[] result = new Object[array1.length + array2.length];
    System.arraycopy(array1, 0, result, 0, array1.length);
    System.arraycopy(array2, 0, result, array1.length, array2.length);
    return result;
  }
}
