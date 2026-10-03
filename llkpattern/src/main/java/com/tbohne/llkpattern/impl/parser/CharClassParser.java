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
    // Bug fix (2026-09-06): this unconditionally built "everything" (complement of the
    // empty set), i.e. always behaved as if DOTALL were on -- the DOTALL flag constant
    // existed (Ll1Pattern.DOTALL) but nothing anywhere ever actually consulted it. Without
    // DOTALL, "." must exclude the line terminators (only '\n' under UNIX_LINES) -- see
    // NamedCharClass.RegexCharacterClass.DOT/DOT_UNIX_LINES,
    // reused directly below, always as "any character" regardless of
    // UNICODE_CHARACTER_CLASS -- "." matching only ASCII by default would be wrong, unlike
    // a POSIX/Unicode-property class like \s or \w where that split is exactly the point.
    ComplexCharacterPatternConstruct dot;
    if ((flags & Pattern.DOTALL) != 0) {
      // "Everything" is exactly an inverted set with no explicit (excluded) entries -- see
      // CodePointSet#invert's doc for why that's always a finite, valid set here rather
      // than the mathematically-unbounded RangeSet Guava's complement() used to produce.
      dot = new ComplexCharacterPatternConstruct(index, EVERY_CODE_POINT);
    } else {
      // Aliased directly, not copied: ComplexCharacterPatternConstruct.ranges is effectively immutable
      // once constructed (see its own doc) -- nothing past this point ever mutates it, so
      // there's no risk of corrupting the shared RegexCharacterClass.DOT.unicode instance,
      // and no allocation is needed at all (unlike rebuilding "\n"'s complement fresh).
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

  /**
   * Adds {@code codePoint} to a bracket expression's members, with everything it matches under {@code
   * CASE_INSENSITIVE} (see {@link CaseFolding}). Folding happens here, per member, rather than on
   * the finished class, because java.util.regex never folds a named class ({@code \w}, a script,
   * ...) or a nested class -- only literal members.
   */
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

  /**
   * The core of {@link #parseComplexCharacter}: parses one {@code "[" IntersectionCharacter "]"}
   * (already-negated if {@code ^} was present) and returns its finished ranges, advancing {@code
   * index} past the closing {@code "]"}. Split out from {@link #parseComplexCharacter} so a nested
   * class (the {@code '['} case below, e.g. {@code "[a-c[p-z]]"}) can recurse straight into this
   * -- merging the result directly into the enclosing accumulator via {@link #mergeInto} -- rather
   * than building a whole separate {@code ComplexCharacterPatternConstruct} object just to immediately discard
   * everything but its {@code ranges}.
   */
  private CodePointSet parseComplexCharacterRanges(int startIndex) {
    boolean negate = false;
    advance(1);
    if (peek == '^') {
      negate = true;
      advance(1);
    }
    // The position of this class's first real content character -- i.e. right after "[" and, if
    // present, "^". A ']' seen exactly here is a literal member (java.util.regex's standard
    // "]-as-first-character" bracket convention, e.g. "[]b]"/"[^]b]" both include ']' itself as a
    // member), not the closing bracket; anywhere else it closes the class as usual.
    int firstContentIndex = index;
    CodePointSetBuilder ranges = CodePointSetBuilder.create();
    // Large sets unioned into the current operand run (a NamedCharClass-backed escape, or a nested
    // "[...]" class) are kept HERE by reference, not copied into `ranges` -- see UnionCodePointSet's
    // own doc for why (avoids copying e.g. \p{L}'s hundreds of ranges just to combine it with a
    // couple of individual bracket members). null until the first such contribution is seen.
    @Nullable CodePointSet runUnion = null;
    // IntersectionCharacter -> UnionCharacter (&& IntersectionCharacter)?  -- "&&" is a real
    // operator token, not tied to a bracket: [a-z&&aeiou] intersects the *whole run* of members
    // up to the next "&&" or the closing "]" against everything accumulated so far, whether or
    // not that run happens to be wrapped in its own "[...]". So `ranges`/`runUnion` below always
    // accumulate only the *current* union-operand run; `intersectionSoFar` (null until the first
    // "&&" is seen) holds the running intersection of every completed operand run before it. A
    // CodePointSetBuilder, not a MutableCodePointSet, since members of a single operand run arrive
    // in whatever order the bracket expression wrote them (e.g. "[cba]" adds 'c', 'b', 'a') --
    // CodePointSetBuilder#append is a plain O(1)-amortized append regardless of order, deferring the
    // sort/coalesce ArrayCodePointSet#insert would otherwise do on every single-character member to
    // one #build() call when this operand run is actually finished (at "&&" or the closing "]").
    @Nullable CodePointSet intersectionSoFar = null;
    for (; ; ) {
      switch (peek) {
        case EOF:
          throw throwUnexpectedChar("expected \"]\" to match ", new CodePointReference(startIndex));
        case ']':
          if (index > firstContentIndex) {
            advance(1); // consume the ']' -- callers expect peek to be past this construct
            if (intersectionSoFar == null) {
              // negate applies to this run alone, so it can be pushed into mergeRun -- see that
              // method's own doc for why that avoids an extra complement() copy in the common case.
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
            // A standalone escape (\D, \p{...}, etc.) is a NamedCharClass-backed constant, often
            // with far more ranges than this bracket expression's own individual members -- union
            // it in lazily instead of copying its entries into `ranges` (see UnionCodePointSet).
            CodePointSet escapeSet = parseComplexEscape();
            runUnion = runUnion == null ? escapeSet : new UnionCodePointSet(runUnion, escapeSet);
          }
          break;
        case '[':
          // RangeCharacter -> "[" IntersectionCharacter "]" -- a nested class is itself a member
          // of the enclosing union, e.g. "[a-c[p-z]]" or an operand of "&&" in "[[a-b]&&[c-d]]".
          // Union its ranges into the current operand run (lazily, same reasoning as the escape
          // case above) -- "&&" (below) intersects whole runs, not individual members, so this is
          // exactly like unioning in any other member.
          CodePointSet nested = parseComplexCharacterRanges(index);
          runUnion = runUnion == null ? nested : new UnionCodePointSet(runUnion, nested);
          break;
        case '&':
          if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '&') {
            // "&&" is always the intersection operator here -- unlike a lone "&", which is just a
            // literal character (handled by falling through to default below) -- regardless of
            // what comes right after it. The RHS is NOT required to be bracketed: [a-z&&aeiou] is
            // valid Java regex syntax, intersecting against the literal run "aeiou", not just
            // [a-z&&[aeiou]].
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

  /**
   * Parses the escape at {@code peek} ({@code \d}, {@code \p{...}}, etc. -- not a single-char
   * escape like {@code \n}, which {@link #tryParseSingleCharEscape} already handles before a
   * caller ever reaches here), advancing past it, and returns the {@link CodePointSet} it denotes.
   *
   * <p>A pure function rather than one that mutates a {@code ComplexCharacterPatternConstruct}/{@code ranges}
   * parameter in place: every result here is either a {@code NamedCharClass}/{@code
   * RegexCharacterClass} static constant or a freshly built complement of one, both safe to hand
   * back directly. A caller building a standalone {@code ComplexCharacterPatternConstruct} for just this escape
   * (the common case -- a bare {@code \d} in running pattern text) can then assign the result
   * straight into that construct's (effectively immutable) {@code ranges} with no defensive copy;
   * a caller merging this into an already-accumulating bracket-expression {@code ranges} (e.g.
   * {@code [a\d]}) still goes through {@code putAll} itself, same as merging any other member.
   */
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
        // Bug fix (2026-09-14): was string-concatenated directly ("escape \"" + peek + "\" ..."),
        // which rendered as `peek`'s raw int value (e.g. "escape "68" not in...") now that `peek`
        // is int-typed rather than char-typed -- wrap in CodePoint instead, same as every other
        // character embedded in an exception message in this file.
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
    // Kept for error messages below -- charClassName itself gets its prefix stripped ("Is"/"In"/
    // "script="/etc.) before we're done, and a thrown message should always echo what the user
    // actually typed, not the stripped-down name used for the NamedCharClass.valueOf() lookup.
    String originalCharClassName = charClassName;
    // The prefixes are checked on `charClassName`, not `peek`: by now the parser's lookahead is
    // already past the whole "\p{...}" escape, so `peek` is the character AFTER it.
    // The two predicates named "Digit" (POSIX vs Unicode) need no special-casing here: get()
    // picks the ASCII/flag-sensitive or always-full-Unicode set from the prefix.
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
      // Bug fix (2026-09-06): `positive` (true for "\p", false for "\P") was computed above but
      // never actually used -- "\P{...}" silently behaved exactly like "\p{...}" (always positive).
      // complement(), not a materialized walk: see NamedCharClass's own complement-based constants
      // for why this is O(namedRanges' entry count), not O(the domain) -- an else-value fill, not
      // an eager enumeration.
      return positive ? namedRanges : namedRanges.complement();
    } catch (IllegalArgumentException e) {
      throw throwUnexpectedChar("unknown named character class \"", originalCharClassName, "\"");
    }
  }

  /** Scans a {@code {name}} property name (peek is at the opening brace) and advances past the closing brace. */
  private String parseBracedClassName() {
    advance(1);
    // Check-then-advance, so `end` always reflects the true (0-or-more) length of the name scanned
    // and the name's very first character is validated too.
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
      // Digits and '-' are needed for block names like "Latin-1Supplement"/"Latin_1_Supplement".
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
