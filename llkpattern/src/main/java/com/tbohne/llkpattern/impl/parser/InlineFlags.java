package com.tbohne.llkpattern.impl.parser;

import com.tbohne.llkpattern.impl.unicode.UnicodeFlags;
import java.util.regex.Pattern;

/** The letters of an inline flag group such as {@code (?i-s:...)}. */
final class InlineFlags {
  private InlineFlags() {}

  /** The {@code Pattern} flag that {@code letter} names, or 0 if it names none. */
  static int valueOf(int letter) {
    switch (letter) {
      case 'i':
        return UnicodeFlags.CASE_INSENSITIVE;
      case 'd':
        return Pattern.UNIX_LINES;
      case 'm':
        return Pattern.MULTILINE;
      case 's':
        return Pattern.DOTALL;
      case 'u':
        return UnicodeFlags.UNICODE_CASE;
      case 'x':
        return Pattern.COMMENTS;
      case 'U':
        return UnicodeFlags.UNICODE_CHARACTER_CLASS;
      default:
        return 0;
    }
  }
}
