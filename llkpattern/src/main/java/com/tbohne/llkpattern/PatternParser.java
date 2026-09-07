package com.tbohne.llkpattern;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import com.tbohne.llkpattern.NamedCharClass.*;
import com.tbohne.llkpattern.PatternConstruct.*;
import com.tbohne.llkpattern.PatternConstruct.BoundaryConstruct.BoundaryEnum;
import com.tbohne.llkpattern.PatternSyntaxException.CodePoint;
import com.tbohne.llkpattern.PatternSyntaxException.CodePointReference;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

final class PatternParser {
  // Notes:
  // https://en.wikipedia.org/wiki/LL_parser
  // https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html
  // https://www.unicode.org/reports/tr44/#GC_Values_Table
  // https://en.wikipedia.org/wiki/LL_parser#Parser_implementation_in_C++
  //
  // This isn't formal BNF. I've taken liberties mixing in regex to compress it slightly
  // UnionConstruct -> ε
  // UnionConstruct -> SequenceConstruct ("|" UnionConstruct)?
  // SequenceConstruct -> "(" Group ")" QuantifierConstruct? SequenceConstruct?
  // SequenceConstruct -> Text QuantifierConstruct? SequenceConstruct?
  // Group -> "?" "<" GroupName ">" ) UnionConstruct
  // Group -> "?" ":" UnionConstruct
  // Group -> "?" "=" UnionConstruct
  // Group -> "?" "!" UnionConstruct
  // Group -> "?" "<=" UnionConstruct
  // Group -> "?" "<!" UnionConstruct
  // Group -> "?" ">" UnionConstruct
  // Group -> UnionConstruct
  // QuantifierConstruct -> "?" ReluctantQuantifier?
  // QuantifierConstruct -> "*" ReluctantQuantifier?
  // QuantifierConstruct -> "+" ReluctantQuantifier?
  // QuantifierConstruct -> "{" Number ("," Number?)? "}" ReluctantQuantifier?
  // ReluctantQuantifier -> "?"
  // ReluctantQuantifier -> "+"
  // GroupName -> [A-Za-z0-9]
  // Text -> "\" "n" Text? //BackReferenceIdConstruct
  // Text -> "\" "k" "<" GroupName ">" Text? // BackReferenceStringConstruct - Note this isn't actually context-free
  // Text -> "\" "Q" ([^\][^E])* "\" "E" Text? // Technically this is LL(2), but it doesn't impact speed much here.
  // Text -> CharacterConstruct Text?  // LiteralConstruct
  // CharacterConstruct -> "[" "^"? IntersectionCharacter
  // CharacterConstruct -> "." // PatternConstruct.DOT
  // CharacterConstruct -> "^" // PatternConstruct.BEGIN_LINE
  // CharacterConstruct -> "$" // PatternConstruct.END_LINE
  // CharacterConstruct -> TerminalCharacter
  // IntersectionCharacter -> UnionCharacter (&& IntersectionCharacter)?
  // UnionCharacter -> RangeCharacter UnionCharacter? //or ListOfCharacters
  // RangeCharacter -> TerminalCharacter ("-" TerminalCharacter)?
  // RangeCharacter -> "[" IntersectionCharacter "]"
  // TerminalCharacter -> "\" EscapeCharacter
  // TerminalCharacter -> [terminal]
  // EscapeCharacter -> [\\\|()?*+{}\[\]^$.]"
  // EscapeCharacter -> [tnrfaeR]
  // EscapeCharacter -> "0" Octal Octal?
  // EscapeCharacter -> "0" Octal3 Octal Octal
  // EscapeCharacter -> "x" Hex Hex
  // EscapeCharacter -> "x" "{" Hex Hex Hex Hex "}"
  // EscapeCharacter -> "u" Hex Hex Hex Hex
  // EscapeCharacter -> "c" [terminal] //control characters
  // EscapeCharacter -> [dDhHsSvVwW] //misc CharacterConstruct
  // EscapeCharacter -> [bBAGZz] //misc CharacterConstruct
  // EscapeCharacter -> "p" "{" Predefined "}"
  // EscapeCharacter -> "P" "{" Predefined "}" //negation
  // Predefined -> "Lower" | "Upper" | "ASCII" | "Alpha" | "Digit" | "Alnum" | "Punct" //misc CharacterConstruct
  // Predefined -> "Graph" | "Print" | "Blank" |"Cntrl" | "XDigit" | "Space" //misc CharacterConstruct
  // Predefined -> "javaLowerCase" | "javaUpperCase" | "javaWhitespace" | "javaMirrored" //misc CharacterConstruct
  // Predefined -> "I" "s" ScriptOrBinaryPropertyOrCategory // :(
  // Predefined -> "I" "n" Block
  // Predefined -> "L" Category
  // Predefined -> Category
  // ScriptOrBinaryPropertyOrCategory -> Script
  // ScriptOrBinaryPropertyOrCategory -> BinaryProperty
  // ScriptOrBinaryPropertyOrCategory -> Category
  // BinaryProperty -> "Alphabetic" | "Ideographic" | "Letter" | "Lowercase" | "Uppercase"
  // BinaryProperty -> "Titlecase" | "Punctuation" | "Control" | "White_Space" | "Digit"
  // BinaryProperty -> "Hex_Digit" | "Join_Control" | "Noncharacter_Code_Point" | "Assigned"
  // Category -> https://www.unicode.org/reports/tr44/#GC_Values_Table  //CharCategoryCharacter
  // Script -> https://www.unicode.org/reports/tr44/#Scripts.txt //CharScriptCharacter
  // Block -> https://www.unicode.org/reports/tr44/#Blocks.txt //CharBlockCharacter

  private final String pattern;
  private int flags;
  private int index;
  private char peek;
  private int quantifiableIndex;
  private int captureConstructIndex;
  // Name -> captureConstructIndex, populated as each named group's real index is assigned (see
  // parseGroup). Exposed via getNamedGroups() for Ll1Pattern to carry forward for group(String).
  private final Map<String, Integer> namedGroups = new HashMap<>();

  PatternParser(String pattern, int flags) {
    this.pattern = pattern;
    index = 0;
    peek = pattern.length() > 0 ? pattern.charAt(0) : '\0';
    this.flags = flags;
    quantifiableIndex = 0;
    captureConstructIndex = 0;
  }

  /** Total number of quantifiable (?, *, +, {n,m}) constructs -- sizes Matcher#quantifiableCounts. */
  int getQuantifiableCount() {
    return quantifiableIndex;
  }

  /** Total number of capturing groups -- sizes Matcher#captureGroups. */
  int getCaptureGroupCount() {
    return captureConstructIndex;
  }

  /** Named capturing groups' names mapped to their captureConstructIndex. */
  Map<String, Integer> getNamedGroups() {
    return namedGroups;
  }

  private void advanceCodePoint() {
    // offsetByCodePoints(index, 1) already returns the new absolute index one code point past
    // `index` -- it's not a delta to add to `index` (that was the bug: it double-advanced every
    // call after the first, since index==0 made `index += offset` and `index = offset` coincide).
    index = pattern.offsetByCodePoints(index, 1);
    peek = index < pattern.length() ? pattern.charAt(index) : '\0';
  }

  private void advance(int count) {
    index += count;
    peek = index < pattern.length() ? pattern.charAt(index) : '\0';
  }

  PatternConstruct parse() {
    QuantifiedUnion root = new QuantifiedUnion(pattern, 0, flags);
    root.flags = flags;
    // The whole pattern isn't a capturing group -- only parseGroup() should assign a real
    // captureConstructIndex. Without this, root's default (0, same as an unassigned real group)
    // was misread as "this is capturing group 0" by anything checking captureConstructIndex != -1.
    root.captureConstructIndex = -1;
    root = parseUnion(root);
    if (index < pattern.length()) {
      // This can trigger if the user has one too many ')'
      throw throwUnexpectedChar("Too many \")\". Check that the () parenthesis match");
    }
    return (root.constructs.size() == 1) ? root.constructs.get(0) : root;
  }

  private QuantifiedUnion parseUnion(QuantifiedUnion parent) {
    Sequence sequence = new Sequence(index);
    int rawTextStartIndex = -1;
    StringBuilder rawText = new StringBuilder();
    for (; ; ) {
      if ("()[]|.^$\0".indexOf(peek) > -1) {
        if (rawText.length() > 0) {
          LiteralString literal = new LiteralString(rawTextStartIndex, index, rawText.toString());
          literal.flags = flags;
          sequence.patterns.add(literal);
          rawText.setLength(0);
          rawTextStartIndex = -1;
        }
        switch (peek) {
          case '(':
            sequence.patterns.add(parseGroup());
            break;
          case '[':
            sequence.patterns.add(parseQuantifiable(parseComplexCharacter()));
            break;
          case ']':
            throw throwUnexpectedChar(
                "Not currently in a bracket group. Check that the [] parenthesis match");
          case '|':
            if (sequence.patterns.isEmpty()) {
              throw throwEmptySequence(sequence.startIndex, parent.startIndex);
            } else if (sequence.patterns.size() == 1) {
              parent.constructs.add(sequence.patterns.get(0));
              sequence = new Sequence(index);
              advance(1);
            } else {
              sequence.endIndex = index;
              parent.constructs.add(sequence);
              sequence = new Sequence(index);
              advance(1);
            }
            break;
          case '.':
            // Bug fix (2026-09-06): this unconditionally built "everything" (complement of the
            // empty set), i.e. always behaved as if DOTALL were on -- the DOTALL flag constant
            // existed (Ll1Pattern.DOTALL) but nothing anywhere ever actually consulted it. Without
            // DOTALL, "." must exclude the line terminator '\n' -- see
            // NamedCharClass.RegexCharacterClass.DOT, which already defines exactly this set (a
            // fuller line-terminator set -- \r, U+0085, U+2028, U+2029 -- and UNIX_LINES
            // interaction are tracked separately in remaining_work.md, not done here). This used to
            // build the same set inline instead of reusing that constant, to dodge a circular
            // static-initialization dependency between NamedCharClass and RegexCharacterClass --
            // now fixed (see remaining_work.md), so reusing it here is safe again. Deliberately
            // .unicode, not .get(flags): DOT's single-arg constructor auto-derives .ascii as
            // .unicode intersected with the ASCII range (right, for a POSIX/Unicode-property class
            // like \s or \w, where that's exactly the ASCII-vs-Unicode distinction
            // UNICODE_CHARACTER_CLASS controls) -- but "." matching only ASCII characters by
            // default would be wrong; "." always means "any character" (modulo the newline
            // exclusion here), regardless of UNICODE_CHARACTER_CLASS.
            RangeSet<Integer> dotRanges =
                (flags & Pattern.DOTALL) != 0
                    ? TreeRangeSet.<Integer>create().complement()
                    : TreeRangeSet.create(RegexCharacterClass.DOT.unicode);
            ComplexCharacter dot = new ComplexCharacter(index, dotRanges);
            dot.flags = flags;
            sequence.patterns.add(parseQuantifiable(dot));
            advance(1);
            break;
          case '^':
            sequence.patterns.add(new BoundaryConstruct(index, index+1, BoundaryEnum.LineBegin));
            advance(1);
            break;
          case '$':
            sequence.patterns.add(new BoundaryConstruct(index, index+1, BoundaryEnum.LineEnd));
            advance(1);
            break;
          case ')':
          case '\0':
            if (sequence.patterns.isEmpty()) {
              throw throwEmptySequence(sequence.startIndex, parent.startIndex);
            } else {
              sequence.endIndex = index;
              parent.constructs.add(sequence);
              parent.endIndex = index;
              return parent;
            }
        }
      } else if (peek == '\\') {
        int startIndex = index;
        int codePoint = tryParseSingleCharEscape();
        if (codePoint != -1) {
          rawText.appendCodePoint(codePoint);
          if (rawTextStartIndex < 0) {
            rawTextStartIndex = startIndex;
          }
        } else {
          if (rawText.length() > 0) {
            LiteralString literal = new LiteralString(rawTextStartIndex, index, rawText.toString());
            literal.flags = flags;
            sequence.patterns.add(literal);
            rawText.setLength(0);
          }
          BoundaryConstruct boundaryConstruct = tryParseBoundary();
          if (boundaryConstruct != null) {
            sequence.patterns.add(boundaryConstruct);
          } else {
            ComplexCharacter escapeChar = new ComplexCharacter(index);
            escapeChar.flags = flags;
            sequence.patterns.add(parseQuantifiable(parseComplexEscape(escapeChar)));
          }
        }
      } else {
        int startIndex = index;
        if (rawTextStartIndex < 0) {
          rawTextStartIndex = startIndex;
        }
        int fullChar = pattern.codePointAt(index);
        advanceCodePoint();
        if (peek == '{' || peek == '?' || peek == '+' || peek == '*') {
          if (rawText.length() > 0) {
            LiteralString literal = new LiteralString(rawTextStartIndex, index, rawText.toString());
            literal.flags = flags;
            sequence.patterns.add(literal);
            rawText.setLength(0);
          }
          ComplexCharacter complex = new ComplexCharacter(startIndex, fullChar);
          complex.flags = flags;
          complex.endIndex = index;
          sequence.patterns.add(parseQuantifiable(complex));
        } else {
          rawText.appendCodePoint(fullChar);
        }
      }
    }
  }

  @SuppressWarnings("FieldCanBeLocal")
  private final String flagNames = "idmsuxU";

  private final int[] flagValues =
      new int[] {
        Pattern.CASE_INSENSITIVE,
        Pattern.UNIX_LINES,
        Pattern.MULTILINE,
        Pattern.DOTALL,
        Pattern.UNICODE_CASE,
        Pattern.COMMENTS,
        Pattern.UNICODE_CHARACTER_CLASS
      };

  private QuantifiedUnion parseGroup() {
    if (peek != '(') {
      throw new IllegalStateException("entered parseGroup at illegal start point");
    }
    QuantifiedUnion union = new QuantifiedUnion(pattern, index, flags);
    advance(1);
    if (peek == '?') {
      advance(1);
      switch (peek) {
        case '<':
          advance(1);
          if (peek == '=' || peek == '!') {
            // Technically it *can* be supported in non-linear time, so this is more "feature
            // request".
            throw throwUnexpectedChar(
                "lookbehind not supported because it cannot execute in linear time");
          }
          int startName = index;
          while ((peek >= '0' && peek <= '9')
              || (peek >= 'a' && peek <= 'z')
              || (peek >= 'A' && peek <= 'Z')) {
            advance(1);
          }
          if (peek != '>') {
            throw throwUnexpectedChar(
                "Character not allowed in capture name. Expected '>' to match ",
                new CodePointReference(startName));
          }
          if (pattern.charAt(startName) >= '0' && pattern.charAt(startName) <= '9') {
            throw throwUnexpectedChar("First character of capture name must be an ASCII letter.");
          }
          union.captureName = pattern.substring(startName, index);
          advance(1);
          break;
        case ':':
          union.captureConstructIndex = -1;
          advance(1);
          break;
        case '=':
        case '!':
          // Technically it *can* be supported in non-linear time, so this is more "feature
          // request".
          throw throwUnexpectedChar(
              "lookahead not supported because it cannot execute in linear time");
        case 'i':
        case 'd':
        case 'm':
        case 's':
        case 'u':
        case 'x':
        case 'U':
          // Bug fix (2026-09-06): a flags-only construct ("(?s)" or "(?s:...)") is non-capturing,
          // exactly like "(?:...)" (which sets this explicitly, above) -- but this branch never
          // did, leaving QuantifiedUnion's captureConstructIndex at its default of 0, i.e.
          // "capturing group 0". That corrupted the whole pattern's capture bookkeeping: the "(?s)"
          // construct got (wrongly) counted and compiled as a real capturing group despite never
          // going through the increment/parseUnion machinery below (the bare "(?...)" form returns
          // immediately, a few lines down) or, for "(?...:...)", getting wrongly double-processed
          // by that machinery as group 0 on top of whatever real group 0 already existed --
          // producing a captureGroups array sized for 0 real groups while still trying to write
          // into slot 0, an ArrayIndexOutOfBoundsException at match time.
          union.captureConstructIndex = -1;
          int flagIdx = 0;
          int enableFlags = 0;
          int disableFlags = 0;
          while ((flagIdx = flagNames.indexOf(peek)) >= 0) {
            int flagValue = flagValues[flagIdx];
            // Bug fix (2026-09-06): this was "|" instead of "&", which is true as soon as
            // flagValue is nonzero -- i.e. on the very first flag character of ANY inline flag
            // group ((?i), (?m), etc.), since enableFlags starts at 0 and (0 | flagValue) != 0
            // whenever flagValue != 0. That made every inline flag construct throw immediately,
            // not just genuine repeats like "(?ii)". "&" actually tests "is this bit already set".
            if ((enableFlags & flagValue) != 0) {
              throw throwUnexpectedChar(
                  "It doesn't make sense for a group to enable the same flag \"",
                  peek,
                  "\" multiple times.");
            }
            enableFlags |= flagValues[flagIdx];
            advance(1);
          }
          if (peek == '-') {
            advance(1);
            while ((flagIdx = flagNames.indexOf(peek)) >= 0) {
              int flagValue = flagValues[flagIdx];
              if ((enableFlags & flagValue) != 0) {
                throw throwUnexpectedChar(
                    "It doesn't make sense for a group and disable the same flag \"",
                    peek,
                    "\" at the same time.");
              }
              if ((disableFlags & flagValue) != 0) {
                throw throwUnexpectedChar(
                    "It doesn't make sense for a group to disable the same flag \"",
                    peek,
                    "\" multiple times.");
              }
              disableFlags |= flagValues[flagIdx];
              advance(1);
            }
          }
          if (peek == ')') {
            union.endIndex = index;
            advance(1);
            flags = flags | enableFlags & ~disableFlags;
            return union;
          } else if (peek == ':') {
            advance(1);
            flags = flags | enableFlags & ~disableFlags;
            union.tempFlags = true;
          } else {
            throw throwUnexpectedChar("That character is illegal in group special construct.");
          }
          break;
        default:
          throw throwUnexpectedChar("Not a valid group special construct for a capture group.");
      }
    }
    QuantifiedUnion ignored = parseUnion(union);
    if (index == pattern.length()) {
      throw throwUnexpectedChar(
          "expected \")\" to match ", new CodePointReference(union.startIndex));
    }
    if (peek != ')') {
      throw new IllegalStateException("compileBody returned but not at end of the group");
    }
    union.endIndex = index;
    if (union.captureConstructIndex != -1) {
      union.captureConstructIndex = captureConstructIndex++;
      if (!union.captureName.isEmpty()) {
        namedGroups.put(union.captureName, union.captureConstructIndex);
      }
    }
    advance(1);
    parseQuantifiable(union);
    if (union.tempFlags) {
      flags = union.parentFlags;
    }
    return union;
  }

  private ComplexQuantifiedCharacter parseComplexQuantifiedCharacter() {
    if (peek != '[') {
      throw new IllegalStateException("entered parseComplexCharacter at illegal start point");
    }
    return parseQuantifiable(parseComplexCharacter());
  }

  private ComplexCharacter parseComplexCharacter() {
    if (peek != '[') {
      throw new IllegalStateException("entered parseComplexCharacter at illegal start point");
    }
    ComplexCharacter complex = new ComplexCharacter(index);
    complex.flags = flags;
    boolean negate = false;
    advance(1);
    if (peek == '^') {
      negate = true;
      advance(1);
    }
    // IntersectionCharacter -> UnionCharacter (&& IntersectionCharacter)?  -- "&&" is a real
    // operator token, not tied to a bracket: [a-z&&aeiou] intersects the *whole run* of members
    // up to the next "&&" or the closing "]" against everything accumulated so far, whether or
    // not that run happens to be wrapped in its own "[...]". So `complex.ranges` below always
    // accumulates only the *current* union-operand run; `intersectionSoFar` (null until the first
    // "&&" is seen) holds the running intersection of every completed operand run before it.
    @Nullable RangeSet<Integer> intersectionSoFar = null;
    for (; ; ) {
      switch (peek) {
        case '\0':
          throw throwUnexpectedChar("expected \"]\" to match ", new CodePointReference(complex.startIndex));
        case ']':
          if (index > complex.startIndex + 1) {
            int closeBracketIndex = index;
            advance(1); // consume the ']' -- callers expect peek to be past this construct
            RangeSet<Integer> finalRanges =
                intersectionSoFar == null
                    ? complex.ranges
                    : intersect(intersectionSoFar, complex.ranges);
            if (negate) {
              ComplexCharacter negated = new ComplexCharacter(
                  complex.startIndex, closeBracketIndex + 1, finalRanges.complement());
              negated.flags = flags;
              return negated;
            }
            complex.ranges = finalRanges;
            complex.endIndex = closeBracketIndex + 1;
            return complex;
          } else {
            complex.ranges.add(Range.singleton(+']'));
            advance(1);
            break;
          }
        case '-':
          complex.ranges.add(Range.singleton(+'-'));
          advance(1);
          break;
        case '\\':
          int eCodePoint = tryParseSingleCharEscape();
          if (eCodePoint != -1) {
            if (peek == '-') {
              parseMaybeRangePredicate(complex, eCodePoint);
            } else {
              complex.ranges.add(Range.singleton(eCodePoint));
            }
          } else {
            parseComplexEscape(complex);
          }
          break;
        case '[':
          // RangeCharacter -> "[" IntersectionCharacter "]" -- a nested class is itself a member
          // of the enclosing union, e.g. "[a-c[p-z]]" or an operand of "&&" in "[[a-b]&&[c-d]]".
          // Union its ranges into the current operand run; "&&" (below) intersects whole runs,
          // not individual members, so this is exactly like unioning in any other member.
          complex.ranges.addAll(parseComplexCharacter().ranges);
          break;
        case '&':
          if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '&') {
            // "&&" is always the intersection operator here -- unlike a lone "&", which is just a
            // literal character (handled by falling through to default below) -- regardless of
            // what comes right after it. The RHS is NOT required to be bracketed: [a-z&&aeiou] is
            // valid Java regex syntax, intersecting against the literal run "aeiou", not just
            // [a-z&&[aeiou]].
            advance(2);
            intersectionSoFar =
                intersectionSoFar == null
                    ? complex.ranges
                    : intersect(intersectionSoFar, complex.ranges);
            complex.ranges = TreeRangeSet.create();
            break;
          }
          // fallthrough
        default:
          int codePoint = pattern.codePointAt(index);
          advanceCodePoint();
          if (peek == '-') {
            parseMaybeRangePredicate(complex, codePoint);
          } else {
            complex.ranges.add(Range.singleton(codePoint));
          }
      }
    }
  }

  /** {@code a & b}, computed as {@code a - complement(b)} since Guava's {@link RangeSet} has no
   *  in-place intersect. Mutates and returns {@code a}. */
  private static RangeSet<Integer> intersect(RangeSet<Integer> a, RangeSet<Integer> b) {
    a.removeAll(b.complement());
    return a;
  }

  static private final String META_CHARACTERS = "^.[]$()*{}?+|\\";
  static private final String CONTROL_CODES = "@ABCDEFGHIJKLMNOPQRTSTUVWXYZ[\\]^_";
  private int tryParseSingleCharEscape() {
    if (peek != '\\') {
      throw new IllegalStateException("entered tryParseSingleCharEscape at illegal start point");
    }
    int peek2 = index + 1 < pattern.length() ? pattern.charAt(index + 1) : '\0';
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
          octal = octal * 8 + peek - '0';
          advance(1);
          if (peek >= '0' && peek <= '7') {
            octal = octal * 8 + peek - '0';
            advance(1);
            if (octal > 255) {
              throw throwUnexpectedChar(
                  "Octal escapes \\0 must be less than 0400 (decimal 256). Use a unicode hex escape instead \"\\u\".");
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
        int maxDigits = unicodeMode ? 4 : (braces ? 6 : 2);
        int digitCount;
        for (digitCount = 0; digitCount < maxDigits; ++digitCount) {
          if (peek >= '0' && peek <= '9') {
            codePoint = codePoint * 16 + peek - '0';
            advance(1);
          } else if (peek >= 'a' && peek <= 'f') {
            codePoint = codePoint * 16 + 10 + peek - 'a';
            advance(1);
          } else if (peek >= 'A' && peek <= 'F') {
            codePoint = codePoint * 16 + 10 + peek - 'A';
            advance(1);
          } else {
            break;
          }
        }
        if (braces) {
          if (peek == '}') {
            advance(1);
          } else {
            throw throwUnexpectedChar(
                "braced hexadecimal escapes \"\\x{h...h}\" can have no more than 6 hexidecmal "
                    + "digits, and must end in }");
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
      default:
        return -1; // not a single character escape
    }
  }

  private @Nullable BoundaryConstruct tryParseBoundary() {
    if (peek != '\\') {
      throw new IllegalStateException("entered tryParseBoundary at illegal start point");
    }
    int peek2 = index + 1 < pattern.length() ? pattern.charAt(index + 1) : '\0';
    switch (peek2) {
      case 'b':
        advance(2);
        return new BoundaryConstruct(index-2, index, BoundaryEnum.Word);
      case 'B':
        advance(2);
        return new BoundaryConstruct(index-2, index, BoundaryEnum.NonWord);
      case 'A':
        advance(2);
        return new BoundaryConstruct(index-2, index, BoundaryEnum.InputBegin);
      case 'G':
        advance(2);
        return new BoundaryConstruct(index-2, index, BoundaryEnum.PreviousMatchEnd);
      case 'Z':
        advance(2);
        return new BoundaryConstruct(index-2, index, BoundaryEnum.InputEndExceptTerminator);
      case 'z':
        advance(2);
        return new BoundaryConstruct(index-2, index, BoundaryEnum.InputEnd);
    }
    return null;
  }

  private ComplexCharacter parseComplexEscape(ComplexCharacter complex) {
    if (peek != '\\') {
      throw new IllegalStateException("entered parseComplexEscape at illegal start point");
    }
    advance(1);
    if (peek == 'R') {
      complex.ranges.addAll(RegexCharacterClass.R.get(flags));
      advance(1);
      complex.endIndex = index;
      return complex;
    }
    if (peek != 'p' && peek != 'P') {
      try {
        complex.ranges.addAll(RegexCharacterClass.valueOf(Character.toString(peek)).get(flags));
        advance(1);
        complex.endIndex = index;
        return complex;
      } catch (IllegalArgumentException e) {
        throw throwUnexpectedChar(
            "escape \"" + peek + "\" not in [dDhHsSvVwWR]. Is it a non-standard regex escape?");
      }
    }
    // All the rest of this method is parsing named character classes
    boolean positive = peek == 'p';
    advance(1);
    if (peek != '{') {
      throw throwUnexpectedChar("character classes \"\\p{...} must be wrapped in {}");
    }
    advance(1);
    // Bug fix (2026-09-06): this used to increment `end` BEFORE checking pattern.charAt(end),
    // meaning the loop never actually validated the name's very first character (at `index`
    // itself) against [a-zA-Z_=], AND miscounted the name's length by one -- so a genuinely valid
    // single-letter category name (\p{L}, \p{M}, \p{N}, \p{P}, \p{S}, \p{Z}, \p{C} all exist in
    // NamedCharClass) was wrongly rejected by the "must have a name" check below, which compared
    // against the pre-increment convention's off-by-one empty-name marker. Found via
    // UnicodeClassTest. Restructured to check-then-advance so `end` always reflects the true
    // (0-or-more) length of the name actually scanned.
    int end = index;
    for (; ; ) {
      if (end == pattern.length()) {
        throw throwUnexpectedChar(
            "character class ", new CodePointReference(index), " is missing the closing }");
      }
      char c = pattern.charAt(end);
      if (c == '}') {
        break;
      }
      // '_' is required for real Unicode property/prefix names like "White_Space", "Hex_Digit",
      // "Join_Control", "Noncharacter_Code_Point", and the "general_category=" prefix itself --
      // without it, \p{IsWhite_Space} (and friends) couldn't even reach the name-lookup logic
      // below, always failing here first. Found via UnicodeClassTest. See remaining_work.md.
      if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && c != '=' && c != '_') {
        throw throwUnexpectedChar(
            "character classes \"\\p{...} must have names in [a-zA-Z_=]. Name started at ",
            new CodePointReference(index));
      }
      end++;
    }
    if (end == index) {
      throw throwUnexpectedChar("escape character classes must have names");
    }
    String charClassName = pattern.substring(index, end);
    advance(end - index + 1);
    // Bounds-checked like every other lookahead-by-one in this file (e.g.
    // tryParseSingleCharEscape's own `peek2`) -- unguarded, this crashed with
    // StringIndexOutOfBoundsException whenever a "\p{...}"/"\P{...}" construct was the very last
    // thing in the pattern (index + 1 == pattern.length()), e.g. the bare pattern "\p{Cs}".
    int peek2 = index + 1 < pattern.length() ? pattern.charAt(index + 1) : '\0';
    // Bug fix (2026-09-06): this used to branch on `peek`/`peek2`, but `advance(end - index + 1)`
    // just above already moved the parser's lookahead PAST the whole "\p{...}" construct -- so
    // `peek`/`peek2` were actually the character(s) *following* the escape, not the first
    // character(s) of the class name, making every Is/In/java-prefixed class (\p{IsAlphabetic},
    // \p{javaLowerCase}, etc.) fail with "unknown named character class" unless the pattern text
    // happened to coincidentally continue with 'I'/'j'. Fixed to check `charClassName` itself
    // (captured before the advance), which is what these prefixes are actually part of. See
    // remaining_work.md.
    NamedCharClass.CharacterClassPrefix prefix;
    if (charClassName.startsWith("Is")) {
      prefix = NamedCharClass.CharacterClassPrefix.is;
      charClassName = charClassName.substring(2);
    } else if (charClassName.startsWith("In")) {
      prefix = NamedCharClass.CharacterClassPrefix.in;
      charClassName = charClassName.substring(2);
    } else if (charClassName.startsWith("java")) {
      prefix = NamedCharClass.CharacterClassPrefix.java;
    } else {
      int eqPos = charClassName.indexOf('=');
      if (eqPos < 0) {
        prefix = NamedCharClass.CharacterClassPrefix.none;
      } else if (charClassName.startsWith("script=") || charClassName.startsWith("sc=")) {
        prefix = NamedCharClass.CharacterClassPrefix.script;
        charClassName = charClassName.substring(eqPos + 1);
      } else if (charClassName.startsWith("block=") || charClassName.startsWith("blk=")) {
        prefix = NamedCharClass.CharacterClassPrefix.block;
        charClassName = charClassName.substring(eqPos + 1);
      } else if (charClassName.startsWith("general_category=") || charClassName.startsWith("gc=")) {
        prefix = NamedCharClass.CharacterClassPrefix.general_category;
        charClassName = charClassName.substring(eqPos + 1);
      } else {
        throw throwUnexpectedChar(
            "unknown Unicode prefix in character class \"", charClassName, "\"");
      }
    }

    try {
      complex.endIndex = index;
      NamedCharClass namedClass = NamedCharClass.valueOf(charClassName);
      RangeSet<Integer> namedRanges = namedClass.get(prefix, flags);
      // Bug fix (2026-09-06): `positive` (true for "\p", false for "\P") was computed above but
      // never actually used -- "\P{...}" silently behaved exactly like "\p{...}" (always positive).
      // complement() is intentionally left unclamped here (matching every other complement() in
      // this file); ComplexCharacter#validRanges() clamps it to the valid code point domain at the
      // point ranges are turned into a dispatch/entry map, same as [^...] and the other negations.
      complex.ranges.addAll(positive ? namedRanges : namedRanges.complement());
      return complex;
    } catch (IllegalArgumentException e) {
      throw throwUnexpectedChar("unknown named character class \"", charClassName, "\"");
    }
  }

  private void parseMaybeRangePredicate(ComplexCharacter complex, int startCodePoint) {
    if (peek != '-') {
      throw new IllegalStateException("entered parseComplexCharacter at illegal start point");
    }
    advance(1);
    if (peek == ']') {
      complex.ranges.add(Range.singleton(startCodePoint));
      complex.ranges.add(Range.singleton(+'-'));
    } else if (peek == '\\') {
      int endCodePoint = tryParseSingleCharEscape();
      if (endCodePoint == -1) {
        throw throwUnexpectedChar(
            "Maximum of range must be a single character. Alternatively, if you didn't intend to have a range, "
                + "then move "
                + "'-' to be the first character in the []");
      }
      complex.ranges.add(Range.closed(startCodePoint, endCodePoint));
    } else {
      int endCodePoint = pattern.codePointAt(index);
      if (endCodePoint <= startCodePoint) {
        throw throwUnexpectedChar(
            "Maximum of range must be less than the minimum. Alternatively, if you didn't intend to have a range, "
                + "then move "
                + "'-' to be the first character in the []");
      }
      advanceCodePoint();
      complex.ranges.add(Range.closed(startCodePoint, endCodePoint));
    }
  }

  private ComplexQuantifiedCharacter parseQuantifiable(ComplexCharacter construct) {
    // construct.flags is already set by whoever built it (every ComplexCharacter creation site
    // sets it directly, since it also needs the correct value for the never-quantified case, which
    // never reaches here at all).
    return parseQuantifiable(new ComplexQuantifiedCharacter(pattern, index, construct));
  }

  private <T extends QuantifiableConstruct> T parseQuantifiable(T construct) {
    // Records the flags in effect where this (possibly-quantified) construct was written -- see
    // PatternConstruct#flags -- so match-time CASE_INSENSITIVE folding stays scoped to an inline
    // "(?i:...)" group instead of leaking pattern-wide (remaining_work.md's "Inline flag toggles
    // don't actually locally scope anything").
    construct.flags = flags;
    if (peek == '?') {
      construct.min = 0;
      // A bare "?" still needs a counter slot: even with max == 1, the compiled loop dispatch
      // must be able to tell "haven't matched yet" from "already matched once" to reject a second
      // attempt (this was previously missing -- every other quantifier branch below assigns one).
      construct.quantifiableIndex = quantifiableIndex++;
      advance(1);
      construct.endIndex = index;
    } else if (peek == '*') {
      construct.min = 0;
      construct.max = Integer.MAX_VALUE;
      construct.quantifiableIndex = quantifiableIndex++;
      advance(1);
      construct.endIndex = index;
    } else if (peek == '+') {
      construct.max = Integer.MAX_VALUE;
      construct.quantifiableIndex = quantifiableIndex++;
      advance(1);
      construct.endIndex = index;
    } else if (peek == '{') {
      advance(1);
      int end = index;
      int startQuantifierIndex = index;
      while (end < pattern.length() && pattern.charAt(end) >= '0' && pattern.charAt(end) <= '9') {
        ++end;
      }
      if (end == index) {
        throw throwUnexpectedChar(
            "first parameter of explicit quantifier '{' must be a number written with ASCII characters");
      }
      try {
        construct.min = Integer.parseInt(pattern.substring(index, end));
      } catch (NumberFormatException e) {
        throw throwUnexpectedChar(
            "first parameter of explicit quantifier '{' must be less than ", Integer.MAX_VALUE);
      }
      construct.max = construct.min;
      construct.quantifiableIndex = quantifiableIndex++;
      advance(end - index);
      if (peek == ',') {
        advance(1);
        end = index;
        while (end < pattern.length() && pattern.charAt(end) >= '0' && pattern.charAt(end) <= '9') {
          ++end;
        }
        if (end == index) {
          construct.max = Integer.MAX_VALUE;
        } else {
          try {
            construct.max = Integer.parseInt(pattern.substring(index, end));
          } catch (NumberFormatException e) {
            throw throwUnexpectedChar(
                "second parameter of explicit quantifier '{' must be less than ",
                Integer.MAX_VALUE);
          }
          advance(end - index);
        }
        if (peek != '}') {
          throw throwUnexpectedChar(
              "Expected '}' to end quantifier started at ",
              new CodePointReference(startQuantifierIndex));
        }
        advance(1);
      } else if (peek == '}') {
        // Bug fix (2026-09-06): the bare "{n}" (exact-count, no comma) form never checked for or
        // consumed its own closing '}' -- only the "{n,...}" branch above did. Left unconsumed,
        // that '}' was then misread as a literal character immediately after the quantifier (e.g.
        // "a{3}" only actually matched the 3-character string "aaa" followed by a literal '}').
        // See remaining_work.md.
        advance(1);
      } else {
        throw throwUnexpectedChar(
            "Expected ',' or '}' to end quantifier started at ",
            new CodePointReference(startQuantifierIndex));
      }
      construct.endIndex = index;
    }
    if (peek == '?' || peek == '+') {
      // Bug fix (2026-09-06): checked for a trailing '*' instead of '+' -- '*' is never a valid
      // quantifier-suffix character (only '?' for reluctant and '+' for possessive are), so a
      // possessive suffix ("a*+", "a++", "a?+", "a{2,3}+") was never actually consumed, leaving a
      // stray literal '+' in the pattern that broke matching. See remaining_work.md.
      advance(1); // reluctant and possessive quantifers are no-ops in this Pattern
      construct.endIndex = index;
    }
    return construct;
  }

  static Object[] concatObjectArrays(Object[] array1, Object[] array2) {
    Object[] result = Arrays.copyOf(array1, array1.length + array2.length);
    System.arraycopy(array2, 0, result, array1.length, array2.length);
    return result;
  }

  private PatternSyntaxException throwGenericPatternSyntaxException(Object... expectations) {
    throw PatternSyntaxException.throwWithReferences(pattern, index, expectations);
  }

  private PatternSyntaxException throwUnexpectedChar(Object... expectations) {
    Object[] args =
        concatObjectArrays(new Object[] {"Unexpected ", new CodePoint(peek)}, expectations);
    throw PatternSyntaxException.throwWithReferences(pattern, index, args);
  }

  private PatternSyntaxException throwEmptySequence(int sequenceStartIndex, int unionStartIndex) {
    if (sequenceStartIndex != unionStartIndex && unionStartIndex > 0) {
      throw throwGenericPatternSyntaxException(
          "Sequences must always match at least one actual character. Sequence started at ",
          new CodePointReference(sequenceStartIndex),
          " inside group started at ",
          new CodePointReference(unionStartIndex));
    } else if (unionStartIndex > 0) {
      throw throwGenericPatternSyntaxException(
          "Sequences must always match at least one actual character. Group started at ",
          new CodePointReference( unionStartIndex));
    } else {
      throw throwGenericPatternSyntaxException(
          "Sequences must always match at least one actual character.");
    }
  }

}
