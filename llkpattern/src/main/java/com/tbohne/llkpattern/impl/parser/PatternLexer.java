package com.tbohne.llkpattern.impl.parser;

import com.tbohne.llkpattern.impl.unicode.UnicodeFlags;
import static com.tbohne.llkpattern.impl.parser.PatternText.concatObjectArrays;
import static com.tbohne.llkpattern.impl.parser.PatternText.hexDigitValue;

import com.tbohne.llkpattern.PatternSyntaxException;
import com.tbohne.llkpattern.PatternSyntaxException.CodePoint;
import java.util.regex.Pattern;

/**
 * The cursor at the bottom of the parser chain {@code PatternLexer <- CharClassParser <-
 * PatternParser}: the pattern text, the current {@code flags}, the position and the code point
 * under it, plus the readers that need no knowledge of the construct tree.
 *
 * <p>The cursor fields are package-private so the subclasses read them directly. {@code peek} is
 * {@link #EOF} past the end of the pattern. {@code \Q...\E} is removed, and {@code CANON_EQ}
 * applied, before parsing starts, so error positions in such a pattern refer to the rewritten text.
 */
class PatternLexer {
  final String pattern;
  // char[] copy of `pattern` just for codePointAt: String#codePointAt re-checks isLatin1() (compact strings) on
  // every call, Character#codePointAt(char[], int) doesn't (decompiled bytecode in notes.md). One O(n) copy per
  // compile, since it is called per parsed character.
  final char[] patternChars;
  int flags;
  int index;
  // An int, not a char: a char can't hold a supplementary code point (charAt gave a lone surrogate half, and
  // parseComplexEscape's invalid-escape lookup and message used peek's numeric value). Every assignment goes
  // through codePointAt, never charAt.
  int peek;

  PatternLexer(String pattern, int flags) {
    char[] chars = null;
    // LITERAL wins over CANON_EQ, as in java.util.regex.
    if ((flags & Pattern.LITERAL) != 0) {
      this.pattern = pattern;
    } else if ((flags & UnicodeFlags.CANON_EQ) != 0) {
      this.pattern = CanonicalEquivalence.rewrite(
          PatternText.removeQuoting(pattern),
          (flags & UnicodeFlags.CASE_INSENSITIVE) != 0 && (flags & UnicodeFlags.UNICODE_CASE) != 0);
    } else {
      // indexOf(String) is an intrinsic; the old Java loop over the char[] was ~2.8% of Pixel 3a compile samples.
      if (pattern.indexOf("\\Q") >= 0) {
        this.pattern = PatternText.removeQuoting(pattern);
      } else {
        this.pattern = pattern;
        chars = pattern.toCharArray();
      }
    }
    this.patternChars = chars != null ? chars : this.pattern.toCharArray();
    index = 0;
    peek = codePointAt(patternChars, 0);
    this.flags = (flags & UnicodeFlags.UNICODE_CHARACTER_CLASS) != 0 ? flags | UnicodeFlags.UNICODE_CASE : flags;
  }

  // Past the end of the pattern. -1 is never a valid code point, so a literal U+0000 stays an ordinary character.
  static final int EOF = -1;

  private static int codePointAt(char[] chars, int i) {
    return i < chars.length ? Character.codePointAt(chars, i) : EOF;
  }

  final void advanceCodePoint() {
    index += Character.charCount(peek);
    peek = codePointAt(patternChars, index);
  }

  final void advance(int count) {
    index += count;
    peek = codePointAt(patternChars, index);
  }

  // The code point right after `peek`; unlike index + 1, skips a supplementary peek's two code units. Computed
  // via Character.charCount(peek) in general, though every caller has just confirmed peek == '\\'.
  final int peekAfter() {
    return codePointAt(patternChars, index + Character.charCount(peek));
  }

  /**
   * Under {@code COMMENTS}, skips whitespace and {@code #}-to-end-of-line comments at the current position; a
   * no-op otherwise. Called wherever the parser is about to inspect {@code peek} to decide what comes next, so
   * insignificant text is skipped between any two tokens. Deliberately never called inside {@code [...]}
   * (whitespace is significant there, as in java.util.regex) or mid-token in a name/flag list.
   */
  final void skipComments() {
    if ((flags & Pattern.COMMENTS) == 0) {
      return;
    }
    for (; ; ) {
      if (Character.isWhitespace(peek)) {
        // advanceCodePoint(), not advance(1): this skips arbitrary pattern text, which could be a supplementary
        // character.
        advanceCodePoint();
      } else if (peek == '#') {
        // Same: a comment body is arbitrary text.
        while (peek != '\n' && peek != EOF) {
          advanceCodePoint();
        }
      } else {
        return;
      }
    }
  }

  // Indexed by ASCII code: a table lookup instead of String#indexOf's per-char loop on every escape.
  static private final boolean[] IS_META_CHARACTER = new boolean[128];
  static {
    for (char c : "^.[]$()*{}?+|\\".toCharArray()) {
      IS_META_CHARACTER[c] = true;
    }
  }
  static private final String CONTROL_CODES = "@ABCDEFGHIJKLMNOPQRTSTUVWXYZ[\\]^_";
  final int tryParseSingleCharEscape() {
    if (peek != '\\') {
      throw new IllegalStateException("entered tryParseSingleCharEscape at illegal start point");
    }
    int peek2 = peekAfter();
    if (peek2 >= 0 && peek2 < 128 && IS_META_CHARACTER[peek2]) {
      advance(2);
      return peek2;
    }
    switch (peek2) {
      case 't':
        advance(2);
        return '\t';
      case 'n':
        advance(2);
        return '\n';
      case 'r':
        advance(2);
        return '\r';
      case 'f':
        advance(2);
        return '\f';
      case 'a':
        advance(2);
        return '\u0007';
      case 'e':
        advance(2);
        return '\u001B';
      case '0':
        return parseOctalEscape();
      case 'c': // control characters
        return parseControlEscape();
      case 'x':
      case 'u':
        return parseHexEscape(peek2 == 'u');
      case 'N':
        advance(2);
        return parseCharacterName();
      default:
        // As in java.util.regex, a backslash before any non-alphabetic character just quotes it
        // ("\-", "\,", "\ ", "\<", or a non-ASCII character). ASCII letters are reserved for
        // escapes; '1'-'9' are backreferences (handled by the caller).
        if (peek2 != EOF
            && !(peek2 >= 'a' && peek2 <= 'z') && !(peek2 >= 'A' && peek2 <= 'Z')
            && !(peek2 >= '1' && peek2 <= '9')) {
          advance(1);
          advanceCodePoint();
          return peek2;
        }
        return -1; // not a single character escape
    }
  }

  /** Parses {@code \0}, {@code \0n}, {@code \0nn} or {@code \0mnn}, with peek at the backslash. */
  private int parseOctalEscape() {
    advance(2);
    if (peek < '0' || peek > '7') {
      throw throwUnexpectedChar(
          "Octal escapes \\0 must be followed by at least one octal character. Alternatively, if you didn't intend "
              + " to have an octal escape, you may have wanted \\x for hexidecimal escapes.");
    }
    int octal = peek - '0';
    advance(1);
    if (peek >= '0' && peek <= '7') {
      // A second digit is always safe (077 = 63 < 0377), so it is consumed unconditionally.
      octal = octal * 8 + peek - '0';
      advance(1);
      if (peek >= '0' && peek <= '7') {
        // A third digit is consumed only if the value stays <= 0377 (255); otherwise it is a separate
        // literal, as in java.util.regex: "\0600" is "\060" then '0'.
        int withThirdDigit = octal * 8 + peek - '0';
        if (withThirdDigit <= 255) {
          octal = withThirdDigit;
          advance(1);
        }
      }
    }
    return octal;
  }

  /** Parses {@code \cX}, with peek at the backslash. */
  private int parseControlEscape() {
    advance(2);
    int codePoint = CONTROL_CODES.indexOf(peek);
    if (codePoint >= 0) {
      advance(1);
      return codePoint;
    } else if (peek == '?') {
      advance(1);
      return '\u007F'; // delete
    }
    throw throwUnexpectedChar("Control escapes \\c must be in [?A-Z[\\]^_?].");
  }

  /** Parses {@code \xhh}, {@code \x{h...h}} or a four-digit unicode escape, with peek at the backslash. */
  private int parseHexEscape(boolean unicodeMode) {
    advance(2);
    int codePoint = 0;
    boolean braces = !unicodeMode && peek == '{';
    if (braces) {
      advance(1);
    }
    int codePointStart = index;
    // The braced form has no digit-count limit, only a VALUE bound (MAX_CODE_POINT): "\x{00000061}" is valid.
    // Integer.MAX_VALUE stands in for unbounded, and accumulation freezes once out of range so a long digit
    // run can't overflow.
    int maxDigits = unicodeMode ? 4 : (braces ? Integer.MAX_VALUE : 2);
    int digitCount;
    for (digitCount = 0; digitCount < maxDigits; ++digitCount) {
      int digit = hexDigitValue(peek);
      if (digit < 0) {
        break;
      }
      if (codePoint <= Character.MAX_CODE_POINT) {
        codePoint = codePoint * 16 + digit;
      }
      advance(1);
    }
    if (braces) {
      if (peek == '}') {
        advance(1);
      } else {
        throw throwUnexpectedChar(
            "braced hexadecimal escapes \"\\x{h...h}\" must end in }");
      }
      if (digitCount == 0) {
        throw throwUnexpectedChar(
            "braced hexadecimal escapes \"\\x{h...h}\" must have at least 1 hexidecimal "
                + "digit");
      }
      if (codePoint > Character.MAX_CODE_POINT) {
        throw throwUnexpectedChar(
            "codepoint",
            String.format("%x", codePoint),
            " is outside the bounds of valid Unicode characters. The maximum is U+10FFFF.");
      }
    } else if (unicodeMode) {
      if (digitCount != 4) {
        throw throwUnexpectedChar(
            "unicode escapes \"\\uhhhh\" must have at exactly 4 hexadecimal digits");
      }
    } else {
      if (digitCount != 2) {
        throw throwUnexpectedChar(
            "hexadecimal escapes \"\\xhh\" must have at exactly 2 hexadecimal digits");
      }
    }
    return codePoint;
  }

  /** Parses the {@code {name}} of a {@code \N{name}} escape (peek is at the opening brace). */
  private int parseCharacterName() {
    if (peek != '{') {
      throw throwUnexpectedChar(
          "Character names are written \"\\N{name}\", e.g. \"\\N{LATIN SMALL LETTER A}\"");
    }
    int nameStart = index + 1;
    int nameEnd = pattern.indexOf('}', nameStart);
    if (nameEnd < 0) {
      throw throwUnexpectedChar(
          "character name escape \"\\N{name}\" is missing the closing }");
    }
    String name = pattern.substring(nameStart, nameEnd);
    if (!CharacterNames.isAvailable()) {
      throw throwUnexpectedChar(
          "\\N{name} needs Character.codePointOf, which this runtime (Java "
              + System.getProperty("java.version") + ") doesn't have. Use \\x{...} with the "
              + "code point instead");
    }
    int codePoint;
    try {
      codePoint = CharacterNames.codePointOf(name);
    } catch (IllegalArgumentException e) {
      throw throwUnexpectedChar(
          "Unknown character name \"", name, "\" in \\N{name}. Names are the Unicode names, "
              + "e.g. \"LATIN SMALL LETTER A\"; this runtime's Unicode data may predate a newer "
              + "character");
    }
    advance(nameEnd + 1 - index);
    return codePoint;
  }

  final PatternSyntaxException throwGenericPatternSyntaxException(Object... expectations) {
    throw PatternSyntaxException.throwWithReferences(pattern, index, expectations);
  }

  final PatternSyntaxException throwUnexpectedChar(Object... expectations) {
    Object[] args =
        concatObjectArrays(new Object[] {"Unexpected ", new CodePoint(peek)}, expectations);
    throw PatternSyntaxException.throwWithReferences(pattern, index, args);
  }
}
