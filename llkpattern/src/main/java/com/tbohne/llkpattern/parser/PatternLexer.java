package com.tbohne.llkpattern.parser;

import static com.tbohne.llkpattern.parser.PatternText.concatObjectArrays;
import static com.tbohne.llkpattern.parser.PatternText.hexDigitValue;

import com.tbohne.llkpattern.CanonicalEquivalence;
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
  // A char[] copy of `pattern`, used only for codePointAt(int) below: String#codePointAt checks
  // isLatin1() (compact strings, JDK 9+) on every call to pick which internal byte layout to
  // read, on top of the real surrogate-pair check; Character#codePointAt(char[], int) skips that
  // first check entirely, since a char[] has no such dual representation to dispatch on -- see
  // documents/notes.md for the decompiled bytecode confirming this difference (found investigating
  // a suggestion that this project's own Android CPU sampling bore out for Matcher#peek's sibling
  // optimization). One extra O(pattern.length()) copy per compile, worth it since codePointAt is
  // called once per character while parsing.
  final char[] patternChars;
  int flags;
  int index;
  // Bug fix (2026-09-14): was `char`, which can't hold a supplementary code point at all -- every
  // assignment below used to read `pattern.charAt(index)`, a lone surrogate half whenever `index`
  // sits on a supplementary character, not the real code point. Every dispatch site in this file
  // compares `peek` only against fixed ASCII tokens, which happens to make a stale surrogate half
  // compare as "no match" the same way a real supplementary code point would -- but
  // parseComplexEscape's `RegexCharacterClass.valueOf(Character.toString(peek))` invalid-escape-name
  // lookup (and its error message) genuinely used `peek`'s numeric value, and got the wrong one for
  // "\" followed directly by a supplementary character. See codePointAt()/advanceCodePoint()/
  // advance() below -- every `peek` assignment now goes through pattern.codePointAt, never charAt.
  int peek;

  PatternLexer(String pattern, int flags) {
    // LITERAL wins over CANON_EQ, as in java.util.regex.
    if ((flags & Pattern.LITERAL) != 0) {
      this.pattern = pattern;
    } else if ((flags & Pattern.CANON_EQ) != 0) {
      this.pattern = CanonicalEquivalence.rewrite(
          PatternText.removeQuoting(pattern),
          (flags & Pattern.CASE_INSENSITIVE) != 0 && (flags & Pattern.UNICODE_CASE) != 0);
    } else {
      this.pattern = PatternText.removeQuoting(pattern);
    }
    this.patternChars = this.pattern.toCharArray();
    index = 0;
    peek = codePointAt(patternChars, 0);
    this.flags = (flags & Pattern.UNICODE_CHARACTER_CLASS) != 0 ? flags | Pattern.UNICODE_CASE : flags;
  }

  // `EOF` (-1, never a valid code point, so a literal U+0000 in the pattern text stays an ordinary
  // character) is the sentinel for "past the end of the pattern" throughout this class. Every
  // `peek` assignment goes through pattern.codePointAt, never charAt (see `peek`'s own field doc
  // for why that distinction matters for a supplementary code point).
  static final int EOF = -1;

  private static int codePointAt(char[] chars, int i) {
    return i < chars.length ? Character.codePointAt(chars, i) : EOF;
  }

  final void advanceCodePoint() {
    // offsetByCodePoints(index, 1) already returns the new absolute index one code point past
    // `index` -- it's not a delta to add to `index` (that was the bug: it double-advanced every
    // call after the first, since index==0 made `index += offset` and `index = offset` coincide).
    index = pattern.offsetByCodePoints(index, 1);
    peek = codePointAt(patternChars, index);
  }

  final void advance(int count) {
    index += count;
    peek = codePointAt(patternChars, index);
  }

  // The code point right after `peek` -- unlike `index + 1`, correctly skips a supplementary
  // `peek` (2 code units) rather than landing on its low surrogate half. Every call site computes
  // this immediately after confirming peek == '\\' (always 1 code unit), so this is equivalent to
  // codePointAt(index + 1) in practice today -- but computed the fully-general way via
  // Character.charCount(peek) rather than assuming that, consistent with `peek`'s own fix above.
  final int peekAfter() {
    return codePointAt(patternChars, index + Character.charCount(peek));
  }

  /**
   * Under {@code COMMENTS} ({@code (?x)}), strips any run of whitespace and {@code #}-to-end-of-
   * line comments starting at the current position -- a no-op otherwise. Called wherever the
   * parser is about to inspect {@code peek} to decide what comes next (the top of {@code
   * parseUnion}'s main loop, {@code parseQuantifiable}'s entry, and right after a group's opening
   * "(") so that insignificant whitespace/comments are transparently skipped between any two
   * meaningful tokens, matching {@code java.util.regex}'s documented {@code COMMENTS} behavior.
   * Deliberately never called from inside a {@code [...]} character class (it's not in scope
   * there, same as real regex -- whitespace inside a class is always significant) or while
   * scanning a name/flag list mid-token (those have their own tighter grammars).
   */
  final void skipComments() {
    if ((flags & Pattern.COMMENTS) == 0) {
      return;
    }
    for (; ; ) {
      if (Character.isWhitespace(peek)) {
        // advanceCodePoint(), not advance(1) -- this skips arbitrary pattern text (not a fixed
        // ASCII token), which could in principle be a supplementary character (no such code point
        // is actually flagged Unicode whitespace today, but there's no reason to assume that
        // forever, and advanceCodePoint() costs nothing extra when it doesn't matter).
        advanceCodePoint();
      } else if (peek == '#') {
        // Same reasoning: a comment body is arbitrary pattern text up to the next '\n', which can
        // genuinely contain a supplementary character.
        while (peek != '\n' && peek != EOF) {
          advanceCodePoint();
        }
      } else {
        return;
      }
    }
  }

  static private final String META_CHARACTERS = "^.[]$()*{}?+|\\";
  static private final String CONTROL_CODES = "@ABCDEFGHIJKLMNOPQRTSTUVWXYZ[\\]^_";
  final int tryParseSingleCharEscape() {
    if (peek != '\\') {
      throw new IllegalStateException("entered tryParseSingleCharEscape at illegal start point");
    }
    int peek2 = peekAfter();
    if (META_CHARACTERS.indexOf(peek2) >= 0) {
      advance(2);
      return peek2;
    }
    int codePoint;
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
        advance(2);
        if (peek < '0' || peek > '7') {
          throw throwUnexpectedChar(
              "Octal escapes \\0 must be followed by at least one octal character. Alternatively, if you didn't intend "
                  + " to have an octal escape, you may have wanted \\x for hexidecimal escapes.");
        }
        int octal = peek - '0';
        advance(1);
        if (peek >= '0' && peek <= '7') {
          // A second digit is always safe -- the largest 2-digit octal value (077) is 63, well
          // under the 255 (0377) ceiling -- so it's consumed unconditionally.
          octal = octal * 8 + peek - '0';
          advance(1);
          if (peek >= '0' && peek <= '7') {
            // A third digit is only consumed if it wouldn't push the value past 0377 (255) --
            // otherwise it's left as a separate literal character, exactly like
            // java.util.regex's own \0nnn: "\0600" is "\060" (48, ASCII '0') followed by a
            // literal '0', not an error. Peeking the would-be value before advancing (rather
            // than advancing then checking, as this used to) is what makes the "leave it
            // unconsumed" branch possible at all.
            int withThirdDigit = octal * 8 + peek - '0';
            if (withThirdDigit <= 255) {
              octal = withThirdDigit;
              advance(1);
            }
          }
        }
        return octal;
      case 'c': // control characters
        advance(2);
        codePoint = CONTROL_CODES.indexOf(peek);
        if (codePoint >= 0) {
          advance(1);
          return codePoint;
        } else if (peek == '?') {
          advance(1);
          return '\u007F'; // delete
        }
        throw throwUnexpectedChar("Control escapes \\c must be in [?A-Z[\\]^_?].");
      case 'x':
      case 'u':
        boolean unicodeMode = peek2 == 'u';
        advance(2);
        codePoint = 0;
        boolean braces = !unicodeMode && peek == '{';
        if (braces) {
          advance(1);
        }
        int codePointStart = index;
        // Braced form has no real digit-count limit in java.util.regex -- only the resulting VALUE
        // is bounded (checked below via MAX_CODE_POINT), so e.g. "\x{00000061}" (10 digits, all but
        // the last two of them leading zeros) is valid. Integer.MAX_VALUE stands in for "unbounded"
        // for the loop bound below; codePoint accumulation itself is frozen (see the `<=
        // MAX_CODE_POINT` guard inside the loop) once it's already out of range, so an arbitrarily
        // long digit run can never overflow it.
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
