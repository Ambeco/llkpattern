package com.tbohne.llkpattern.impl.parser;

import com.tbohne.llkpattern.impl.unicode.UnicodeFlags;
import static com.tbohne.llkpattern.impl.parser.PatternText.*;

import com.tbohne.llkpattern.impl.unicode.CaseFolding;
import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.CodePointSetBuilder;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import com.tbohne.llkpattern.PatternSyntaxException.CodePoint;
import com.tbohne.llkpattern.PatternSyntaxException.CodePointReference;
import com.tbohne.llkpattern.impl.unicode.UnionCodePointSet;
import com.tbohne.llkpattern.impl.constructs.ComplexCharacterPatternConstruct;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The middle of the parser chain {@code PatternLexer <- CharClassParser <- PatternParser}: every
 * construct that denotes a set of code points -- bracket expressions (nested, negated, {@code &&}),
 * {@code \d}/{@code \p{...}}-style escapes, and {@code .}.
 *
 * <p>{@code CASE_INSENSITIVE} is applied to a class's literal members here, at parse time (see
 * {@link CaseFolding}); a named or nested class is never folded, as in {@code java.util.regex}. The
 * quantifier suffix is left to {@link PatternParser}.
 */
class CharClassParser extends PatternLexer {
  CharClassParser(String pattern, int flags) {
    super(pattern, flags);
  }

  // DOTALL's ".": shared like RegexCharacterClass.DOT, since ComplexCharacterPatternConstruct.ranges is never mutated.
  private static final CodePointSet EVERY_CODE_POINT = buildEveryCodePoint();

  private static CodePointSet buildEveryCodePoint() {
    CodePointSetBuilder everything = CodePointSetBuilder.create();
    everything.invert();
    return everything.build();
  }

  /** {@code .} outside a bracket expression, with {@code peek} still on it; the caller advances. */
  final ComplexCharacterPatternConstruct parseDot() {
    // Without DOTALL, "." excludes line terminators (DOT/DOT_UNIX_LINES). Always "any character" regardless of
    // UNICODE_CHARACTER_CLASS, unlike \s or \w, where that split is the point.
    ComplexCharacterPatternConstruct dot;
    if ((flags & Pattern.DOTALL) != 0) {
      // "Everything" is an inverted set with no excluded entries.
      dot = new ComplexCharacterPatternConstruct(index, EVERY_CODE_POINT);
    } else {
      // Aliased, not copied: ComplexCharacterPatternConstruct.ranges is never mutated, so the shared DOT set is safe.
      dot = new ComplexCharacterPatternConstruct(index, ((flags & Pattern.UNIX_LINES) != 0
          ? RegexCharacterClass.DOT_UNIX_LINES : RegexCharacterClass.DOT).unicode);
    }
    dot.flags = flags;
    dot.residualElse = true;
    return dot;
  }

  final ComplexCharacterPatternConstruct parseComplexCharacter() {
    if (peek != '[') {
      throw new IllegalStateException("entered parseComplexCharacter at illegal start point");
    }
    int startIndex = index;
    CodePointSet finalRanges = parseComplexCharacterRanges(startIndex);
    ComplexCharacterPatternConstruct complex = new ComplexCharacterPatternConstruct(startIndex, index, finalRanges);
    complex.flags = flags;
    return complex;
  }

  // Adds codePoint with everything it matches under CASE_INSENSITIVE (CaseFolding). Folded per member, not on
  // the finished class: java.util.regex never folds a named or nested class.
  private void addLiteral(CodePointSetBuilder ranges, int codePoint) {
    if ((flags & UnicodeFlags.CASE_INSENSITIVE) == 0) {
      ranges.append(codePoint, codePoint + 1);
    } else {
      CaseFolding.addSingle(ranges, codePoint, CaseFolding.isUnicodeCase(flags));
    }
  }

  /** {@link #addLiteral} for an explicit {@code lo-hi} range ({@code max} exclusive). */
  private void addLiteralRange(CodePointSetBuilder ranges, int min, int max) {
    if ((flags & UnicodeFlags.CASE_INSENSITIVE) == 0) {
      ranges.append(min, max);
    } else {
      CaseFolding.appendRange(ranges, min, max, CaseFolding.isUnicodeCase(flags));
    }
  }

  /** A one-code-point {@code ComplexCharacterPatternConstruct} (a literal that had to become a class, e.g. to be quantified). */
  final ComplexCharacterPatternConstruct singleCharacter(int startIndex, int codePoint) {
    if ((flags & UnicodeFlags.CASE_INSENSITIVE) == 0) {
      return new ComplexCharacterPatternConstruct(startIndex, codePoint);
    }
    CodePointSetBuilder members = CodePointSetBuilder.create();
    addLiteral(members, codePoint);
    return new ComplexCharacterPatternConstruct(startIndex, members.build());
  }

  // Parses one "[" IntersectionCharacter "]" (already negated if ^ was present), advancing past the "]". Split
  // out so a nested class ("[a-c[p-z]]") recurses here and merges via mergeInto, without building a throwaway
  // ComplexCharacterPatternConstruct.
  private CodePointSet parseComplexCharacterRanges(int startIndex) {
    boolean negate = false;
    advance(1);
    if (peek == '^') {
      negate = true;
      advance(1);
    }
    // Where this class's first content character sits (after "[" and an optional "^"): a ']' exactly here is a
    // literal member ("[]b]", "[^]b]"); anywhere else it closes the class.
    int firstContentIndex = index;
    CodePointSetBuilder ranges = CodePointSetBuilder.create();
    // Large sets unioned into the current run (a named-class escape, a nested class) are held here by reference,
    // not copied into `ranges` (see UnionCodePointSet). Null until the first.
    @Nullable CodePointSet runUnion = null;
    // "&&" is a real operator token, not tied to a bracket: [a-z&&aeiou] intersects the whole run of members up
    // to the next "&&" or the closing "]" against everything so far. So `ranges`/`runUnion` hold only the current
    // operand run, and `intersectionSoFar` (null until the first "&&") the intersection of completed runs. A
    // CodePointSetBuilder because members arrive in any order ("[cba]"): its append is O(1) amortized, deferring
    // the sort/coalesce to one build() when the run finishes.
    @Nullable CodePointSet intersectionSoFar = null;
    for (; ; ) {
      switch (peek) {
        case EOF:
          throw throwUnexpectedChar("expected \"]\" to match ", new CodePointReference(startIndex));
        case ']':
          if (index > firstContentIndex) {
            advance(1); // consume the ']' -- callers expect peek to be past this construct
            if (intersectionSoFar == null) {
              // negate applies to this run alone, so mergeRun can absorb it (avoids a complement() copy).
              return CodePointSetBuilder.mergeRun(ranges, runUnion, negate);
            }
            CodePointSet completedRun = CodePointSetBuilder.mergeRun(ranges, runUnion, false);
            CodePointSet finalRanges = intersectionSoFar.intersection(completedRun);
            return negate ? finalRanges.complement() : finalRanges;
          } else {
            ranges.append(+']', +']' + 1);
            advance(1);
            break;
          }
        case '-':
          ranges.append(+'-', +'-' + 1);
          advance(1);
          break;
        case '\\':
          int eCodePoint = tryParseSingleCharEscape();
          if (eCodePoint != -1) {
            if (peek == '-') {
              parseMaybeRangePredicate(ranges, eCodePoint);
            } else {
              addLiteral(ranges, eCodePoint);
            }
          } else {
            // A standalone escape is usually a large NamedCharClass constant: union it in lazily rather than
            // copying its entries (UnionCodePointSet).
            CodePointSet escapeSet = parseComplexEscape();
            runUnion = runUnion == null ? escapeSet : new UnionCodePointSet(runUnion, escapeSet);
          }
          break;
        case '[':
          // A nested class is a member of the enclosing union ("[a-c[p-z]]") or an "&&" operand
          // ("[[a-b]&&[c-d]]"); unioned lazily like an escape, since "&&" intersects whole runs.
          CodePointSet nested = parseComplexCharacterRanges(index);
          runUnion = runUnion == null ? nested : new UnionCodePointSet(runUnion, nested);
          break;
        case '&':
          if (index + 1 < patternChars.length && patternChars[index + 1] == '&') {
            // "&&" is always the intersection operator (a lone "&" is a literal), and its right side needn't be
            // bracketed: [a-z&&aeiou] is valid.
            advance(2);
            CodePointSet completedRun = CodePointSetBuilder.mergeRun(ranges, runUnion, false);
            intersectionSoFar =
                intersectionSoFar == null
                    ? completedRun
                    : intersectionSoFar.intersection(completedRun);
            ranges = CodePointSetBuilder.create();
            runUnion = null;
            break;
          }
          // fallthrough
        default:
          int codePoint = Character.codePointAt(patternChars, index);
          advanceCodePoint();
          if (peek == '-') {
            parseMaybeRangePredicate(ranges, codePoint);
          } else {
            addLiteral(ranges, codePoint);
          }
      }
    }
  }

  // Parses the escape at peek (\d, \p{...}, ...; single-char escapes like \n are handled earlier by
  // tryParseSingleCharEscape), advancing past it. Pure: every result is a NamedCharClass/RegexCharacterClass
  // constant or a fresh complement of one, so a caller can assign it into a ComplexCharacterPatternConstruct's
  // immutable ranges with no copy.
  final CodePointSet parseComplexEscape() {
    if (peek != '\\') {
      throw new IllegalStateException("entered parseComplexEscape at illegal start point");
    }
    advance(1);
    if (peek == 'R') {
      CodePointSet result = RegexCharacterClass.R.get(flags);
      advance(1);
      return result;
    }
    if (peek != 'p' && peek != 'P') {
      try {
        CodePointSet result = RegexCharacterClass.valueOf(Character.toString(peek)).get(flags);
        advance(1);
        return result;
      } catch (IllegalArgumentException e) {
        // CodePoint, not raw concatenation: peek is an int, which would render as e.g. "68".
        throw throwUnexpectedChar(
            "escape \"", new CodePoint(peek), "\" not in [dDhHsSvVwWR]. Is it a non-standard "
                + "regex escape?");
      }
    }
    // All the rest of this method is parsing named character classes
    boolean positive = peek == 'p';
    advance(1);
    String charClassName;
    if (peek != '{') {
      // Single-letter form (\pL, \PL): java.util.regex takes exactly one following character as the
      // whole name. Whether it names a real class is left to the lookups below.
      if (peek == EOF) {
        throw throwUnexpectedChar("character classes \"\\p{...} must be wrapped in {}");
      }
      charClassName = new String(Character.toChars(peek));
      advanceCodePoint();
    } else {
      charClassName = parseBracedClassName();
    }
    // The original name, for error messages: charClassName gets its prefix stripped.
    String originalCharClassName = charClassName;
    // Prefixes are checked on charClassName, not peek, which is already past the whole escape. The two "Digit"
    // predicates (POSIX vs Unicode) need no special case: get() picks the set from the prefix.
    CharacterClassPrefix prefix = prefixOf(charClassName);
    if (prefix == null) {
      throw throwUnexpectedChar(
          "unknown Unicode prefix in character class \"", charClassName, "\"");
    }
    charClassName = withoutPrefix(charClassName, prefix);

    // \p{script=Latin} always means a script; \p{IsXxx} tries the named classes/binary properties
    // first and falls back to a script (\p{IsLatin}), same order as java.util.regex.
    if (prefix == CharacterClassPrefix.script
        || prefix == CharacterClassPrefix.is
            && !NamedCharClass.isNamedClass(charClassName)) {
      CodePointSet scriptRanges = NamedCharClass.scriptByName(charClassName);
      if (scriptRanges == null) {
        throw throwUnexpectedChar("unknown named character class \"", originalCharClassName, "\"");
      }
      return positive ? scriptRanges : scriptRanges.complement();
    }

    // \p{InGreek}/\p{block=Greek} are always blocks.
    if (prefix == CharacterClassPrefix.in
        || prefix == CharacterClassPrefix.block) {
      CodePointSet blockRanges = NamedCharClass.blockByName(charClassName);
      if (blockRanges == null) {
        throw throwUnexpectedChar("unknown named character class \"", originalCharClassName, "\"");
      }
      return positive ? blockRanges : blockRanges.complement();
    }

    try {
      NamedCharClass namedClass = prefix == CharacterClassPrefix.is
          ? NamedCharClass.valueOfIs(charClassName)
          : NamedCharClass.valueOf(charClassName);
      CodePointSet namedRanges = namedClass.get(prefix, flags);
      if ((flags & UnicodeFlags.CASE_INSENSITIVE) != 0) {
        namedRanges = namedClass.caseInsensitive(prefix, flags, namedRanges);
      }
      // \P must complement (it once silently behaved like \p); complement() is O(entry count), not O(domain).
      return positive ? namedRanges : namedRanges.complement();
    } catch (IllegalArgumentException e) {
      throw throwUnexpectedChar("unknown named character class \"", originalCharClassName, "\"");
    }
  }

  /** Scans a {@code {name}} property name (peek is at the opening brace) and advances past the closing brace. */
  private String parseBracedClassName() {
    advance(1);
    // Check-then-advance, so `end` is the true name length and the first character is validated too.
    int end = index;
    for (; ; ) {
      if (end == patternChars.length) {
        throw throwUnexpectedChar(
            "character class ", new CodePointReference(index), " is missing the closing }");
      }
      char c = patternChars[end];
      if (c == '}') {
        break;
      }
      // '_' is needed for names like "White_Space" and "Hex_Digit" and the "general_category=" prefix; digits and
      // '-' for block names like "Latin-1Supplement".
      if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9')
          && c != '=' && c != '_' && c != '-') {
        throw throwUnexpectedChar(
            "character classes \"\\p{...} must have names in [a-zA-Z0-9_=-]. Name started at ",
            new CodePointReference(index));
      }
      end++;
    }
    if (end == index) {
      throw throwUnexpectedChar("escape character classes must have names");
    }
    String name = pattern.substring(index, end);
    advance(end - index + 1);
    return name;
  }

  private static final String RANGE_MAX_BELOW_MIN =
      "Maximum of range must not be less than the minimum. Alternatively, if you didn't intend to "
          + "have a range, then move '-' to be the first character in the []";

  private void parseMaybeRangePredicate(CodePointSetBuilder ranges, int startCodePoint) {
    if (peek != '-') {
      throw new IllegalStateException("entered parseComplexCharacter at illegal start point");
    }
    advance(1);
    if (peek == ']') {
      addLiteral(ranges, startCodePoint);
      ranges.append(+'-', +'-' + 1);
    } else if (peek == '\\') {
      int endCodePoint = tryParseSingleCharEscape();
      if (endCodePoint == -1) {
        throw throwUnexpectedChar(
            "Maximum of range must be a single character. Alternatively, if you didn't intend to have a range, "
                + "then move "
                + "'-' to be the first character in the []");
      }
      if (endCodePoint < startCodePoint) {
        throw throwUnexpectedChar(RANGE_MAX_BELOW_MIN);
      }
      addLiteralRange(ranges, startCodePoint, endCodePoint + 1);
    } else {
      int endCodePoint = Character.codePointAt(patternChars, index);
      if (endCodePoint < startCodePoint) {
        throw throwUnexpectedChar(RANGE_MAX_BELOW_MIN);
      }
      advanceCodePoint();
      addLiteralRange(ranges, startCodePoint, endCodePoint + 1);
    }
  }

  /** The prefix {@code name} starts with, or null for an unknown {@code xyz=} one. */
  private static @Nullable CharacterClassPrefix prefixOf(String name) {
    if (name.startsWith("Is")) {
      return CharacterClassPrefix.is;
    } else if (name.startsWith("In")) {
      return CharacterClassPrefix.in;
    } else if (name.startsWith("java")) {
      return CharacterClassPrefix.java;
    } else if (name.startsWith("script=") || name.startsWith("sc=")) {
      return CharacterClassPrefix.script;
    } else if (name.startsWith("block=") || name.startsWith("blk=")) {
      return CharacterClassPrefix.block;
    } else if (name.startsWith("general_category=") || name.startsWith("gc=")) {
      return CharacterClassPrefix.general_category;
    } else if (name.indexOf('=') < 0) {
      return CharacterClassPrefix.none;
    }
    return null;
  }

  // "java" is part of the name itself (javaLowerCase), unlike the other prefixes.
  private static String withoutPrefix(String name, CharacterClassPrefix prefix) {
    if (prefix == CharacterClassPrefix.is || prefix == CharacterClassPrefix.in) {
      return name.substring(2);
    } else if (prefix == CharacterClassPrefix.script
        || prefix == CharacterClassPrefix.block
        || prefix == CharacterClassPrefix.general_category) {
      return name.substring(name.indexOf('=') + 1);
    }
    return name;
  }
}
