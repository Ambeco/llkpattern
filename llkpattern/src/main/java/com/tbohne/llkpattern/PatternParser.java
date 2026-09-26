package com.tbohne.llkpattern;

import com.tbohne.llkpattern.CodePointSet.MutableCodePointSet;
import com.tbohne.llkpattern.NamedCharClass.*;
import com.tbohne.llkpattern.PatternConstruct.*;
import com.tbohne.llkpattern.PatternConstruct.BoundaryConstruct.BoundaryEnum;
import com.tbohne.llkpattern.PatternSyntaxException.CodePoint;
import com.tbohne.llkpattern.PatternSyntaxException.CodePointReference;

import java.nio.CharBuffer;
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
  // Group -> "?" "=" UnionConstruct   // rejected at parse time: can't run in linear time
  // Group -> "?" "!" UnionConstruct   // rejected at parse time: can't run in linear time
  // Group -> "?" "<=" UnionConstruct  // only when UnionConstruct always matches exactly one code point
  // Group -> "?" "<!" UnionConstruct  // only when UnionConstruct always matches exactly one code point
  // Group -> "?" ">" UnionConstruct   // atomic group: not implemented
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
  // Text -> "\" "Q" ([^\][^E])* "\" "E" Text? // Not implemented yet. Technically this is LL(2), but it doesn't impact speed much here.
  // Text -> CharacterConstruct Text?  // LiteralConstruct
  // CharacterConstruct -> "[" "^"? IntersectionCharacter
  // CharacterConstruct -> "." // PatternConstruct.DOT
  // CharacterConstruct -> "^" // PatternConstruct.BEGIN_LINE
  // CharacterConstruct -> "$" // PatternConstruct.END_LINE
  // CharacterConstruct -> TerminalCharacter
  // IntersectionCharacter -> UnionCharacter (&& IntersectionCharacter)?
  // UnionCharacter -> RangeCharacter UnionCharacter? //or ListOfCharacters
  // RangeCharacter -> TerminalCharacter ("-" TerminalCharacter)?  // max must not be below min
  // RangeCharacter -> "[" IntersectionCharacter "]"
  // TerminalCharacter -> "\" EscapeCharacter
  // TerminalCharacter -> [terminal]
  // EscapeCharacter -> [^A-Za-z1-9]  // quotes any other character (e.g. \\ \| \- \, \< or non-ASCII);
  //                                  // ASCII letters are reserved for escapes, 1-9 for backreferences
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
  // EscapeCharacter -> [pP] [A-Za-z]  // single-letter form, e.g. \pL: the letter is the whole Predefined name
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
  // ScriptOrBinaryPropertyOrCategory -> PosixName  // "Lower" | "Upper" | "ASCII" | ... as above, but always full-Unicode
  // BinaryProperty -> "Alphabetic" | "Ideographic" | "Letter" | "Lowercase" | "Uppercase"
  // BinaryProperty -> "Titlecase" | "Punctuation" | "Control" | "White_Space" | "Digit"
  // BinaryProperty -> "Hex_Digit" | "Join_Control" | "Noncharacter_Code_Point" | "Assigned"
  // Category -> https://www.unicode.org/reports/tr44/#GC_Values_Table  //CharCategoryCharacter
  // Script -> https://www.unicode.org/reports/tr44/#Scripts.txt //CharScriptCharacter
  // Block -> https://www.unicode.org/reports/tr44/#Blocks.txt //CharBlockCharacter

  private final String pattern;
  // A char[] copy of `pattern`, used only for codePointAt(int) below: String#codePointAt checks
  // isLatin1() (compact strings, JDK 9+) on every call to pick which internal byte layout to
  // read, on top of the real surrogate-pair check; Character#codePointAt(char[], int) skips that
  // first check entirely, since a char[] has no such dual representation to dispatch on -- see
  // documents/notes.md for the decompiled bytecode confirming this difference (found investigating
  // a suggestion that this project's own Android CPU sampling bore out for Matcher#peek's sibling
  // optimization). One extra O(pattern.length()) copy per compile, worth it since codePointAt is
  // called once per character while parsing.
  private final char[] patternChars;
  private int flags;
  private int index;
  // Bug fix (2026-09-14): was `char`, which can't hold a supplementary code point at all -- every
  // assignment below used to read `pattern.charAt(index)`, a lone surrogate half whenever `index`
  // sits on a supplementary character, not the real code point. Every dispatch site in this file
  // compares `peek` only against fixed ASCII tokens, which happens to make a stale surrogate half
  // compare as "no match" the same way a real supplementary code point would -- but
  // parseComplexEscape's `RegexCharacterClass.valueOf(Character.toString(peek))` invalid-escape-name
  // lookup (and its error message) genuinely used `peek`'s numeric value, and got the wrong one for
  // "\" followed directly by a supplementary character. See codePointAt()/advanceCodePoint()/
  // advance() below -- every `peek` assignment now goes through pattern.codePointAt, never charAt.
  private int peek;
  private int quantifiableIndex;
  private int captureConstructIndex;
  // \G doesn't match any specific position in the input -- per the project owner (2026-09-07),
  // it's really just a flag saying "anchor find() to exactly where the previous match ended,
  // don't scan forward looking for a later one" (Matcher#matchEnd already tracks that position),
  // so it has no PatternConstruct/MatcherConstruct representation at all. Set when \G is
  // recognized (see tryParseBoundary's caller); exposed via anchorsToPreviousMatchEnd() for
  // Ll1Pattern to carry forward for Matcher#find() to consult.
  private boolean anchorsToPreviousMatchEnd = false;
  // Name -> captureConstructIndex, populated as each named group's real index is assigned (see
  // parseGroup). Exposed via getNamedGroups() for Ll1Pattern to carry forward for group(String).
  private final Map<String, Integer> namedGroups = new HashMap<>();
  // captureConstructIndex -> the already-fully-parsed QuantifiedUnion for that group, populated at
  // the same point as namedGroups (parseGroup, once a group's ")" is reached). Backreferences
  // (tryParseBackReference) look a referenced group up here: only a group already present -- i.e.
  // already closed, textually before the "\1"/"\k<name>" -- can be referenced; anything else is a
  // forward reference or an undefined group, both rejected at parse time. See design.md's
  // "Backreferences" section.
  private final Map<Integer, QuantifiedUnion> closedGroupsByIndex = new HashMap<>();

  PatternParser(String pattern, int flags) {
    // LITERAL wins over CANON_EQ, as in java.util.regex.
    if ((flags & Pattern.LITERAL) != 0) {
      this.pattern = pattern;
    } else if ((flags & Pattern.CANON_EQ) != 0) {
      this.pattern = CanonicalEquivalence.rewrite(
          removeQuoting(pattern),
          (flags & Pattern.CASE_INSENSITIVE) != 0 && (flags & Pattern.UNICODE_CASE) != 0);
    } else {
      this.pattern = removeQuoting(pattern);
    }
    this.patternChars = this.pattern.toCharArray();
    index = 0;
    peek = codePointAt(0);
    this.flags = (flags & Pattern.UNICODE_CHARACTER_CLASS) != 0 ? flags | Pattern.UNICODE_CASE : flags;
    quantifiableIndex = 0;
    captureConstructIndex = 0;
  }

  /**
   * Rewrites every {@code \Q...\E} span into escaped literal characters, as {@code
   * java.util.regex} does, so the rest of the parser never sees quotation: ASCII non-alphanumerics
   * get a backslash, everything else is copied verbatim. An unterminated {@code \Q} quotes to the
   * end of the pattern. Error positions in a pattern containing quotation refer to the rewritten
   * text. Returns {@code pattern} itself when it contains no {@code \Q}.
   */
  private static String removeQuoting(String pattern) {
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

  /** Whether the pattern used {@code \G} -- see the field's own doc for what that means. */
  boolean anchorsToPreviousMatchEnd() {
    return anchorsToPreviousMatchEnd;
  }

  // `EOF` (-1, never a valid code point, so a literal U+0000 in the pattern text stays an ordinary
  // character) is the sentinel for "past the end of the pattern" throughout this class. Every
  // `peek` assignment goes through pattern.codePointAt, never charAt (see `peek`'s own field doc
  // for why that distinction matters for a supplementary code point).
  private static final int EOF = -1;

  private int codePointAt(int i) {
    return i < patternChars.length ? Character.codePointAt(patternChars, i) : EOF;
  }

  private void advanceCodePoint() {
    // offsetByCodePoints(index, 1) already returns the new absolute index one code point past
    // `index` -- it's not a delta to add to `index` (that was the bug: it double-advanced every
    // call after the first, since index==0 made `index += offset` and `index = offset` coincide).
    index = pattern.offsetByCodePoints(index, 1);
    peek = codePointAt(index);
  }

  private void advance(int count) {
    index += count;
    peek = codePointAt(index);
  }

  // The code point right after `peek` -- unlike `index + 1`, correctly skips a supplementary
  // `peek` (2 code units) rather than landing on its low surrogate half. Every call site computes
  // this immediately after confirming peek == '\\' (always 1 code unit), so this is equivalent to
  // codePointAt(index + 1) in practice today -- but computed the fully-general way via
  // Character.charCount(peek) rather than assuming that, consistent with `peek`'s own fix above.
  private int peekAfter() {
    return codePointAt(index + Character.charCount(peek));
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
  private void skipComments() {
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

  PatternConstruct parse() {
    if ((flags & Pattern.LITERAL) != 0) {
      return parseLiteralPattern();
    }
    // The whole pattern isn't a capturing group -- only parseGroup() should assign a real
    // captureConstructIndex (-1 here, same as parseUnion's non-capturing callers). A QuantifiedUnion
    // is only actually allocated (by parseUnion) if the pattern has a top-level "|"; a single
    // alternative comes back as a bare Sequence, same shape #parse() has always returned for that
    // case.
    PatternConstruct root = parseUnion(0, flags, /* captureConstructIndex= */ -1, /* captureName= */ "");
    if (index < pattern.length()) {
      // This can trigger if the user has one too many ')'
      throw throwUnexpectedChar("Too many \")\". Check that the () parenthesis match");
    }
    return root;
  }

  /**
   * {@code LITERAL}: the whole pattern is plain text, with no metacharacters, escapes, quotation
   * or inline flags. Only {@code CASE_INSENSITIVE}/{@code UNICODE_CASE} still affect matching,
   * as in {@code java.util.regex}. Returns the same bare {@code Sequence} shape {@link #parse()}
   * gives any single-alternative pattern.
   */
  private PatternConstruct parseLiteralPattern() {
    if (pattern.isEmpty()) {
      throw throwEmptySequence(0, 0);
    }
    Sequence sequence = new Sequence(0);
    LiteralString literal = new LiteralString(0, pattern.length(), pattern);
    literal.flags = flags;
    sequence.patterns.add(literal);
    sequence.endIndex = pattern.length();
    index = pattern.length();
    return sequence;
  }

  /**
   * Parses one or more {@code '|'}-separated alternatives starting at the current position, up to
   * (not consuming) the matching {@code ')'} or end of pattern. {@code unionStartIndex}/{@code
   * unionFlags} are the position/flags a wrapping {@code QuantifiedUnion} would have been
   * constructed with had one been pre-allocated by the caller (the old design); {@code
   * captureConstructIndex}/{@code captureName} are that union's capture identity, or -1/"" for a
   * non-capturing caller (a plain group body, a lookbehind body, or the whole pattern).
   *
   * <p>A {@code QuantifiedUnion} is only actually allocated when one is structurally required: more
   * than one alternative was found, or {@code captureConstructIndex != -1} (a capturing group
   * always needs a real object to carry its index for backreferences/{@code closedGroupsByIndex}).
   * Otherwise (exactly one alternative, non-capturing) the bare {@code Sequence} is returned
   * directly -- the caller (parseGroup's {@code quantifyGroupBody}, or #parse()) is responsible for
   * wrapping it later if it turns out to need quantifying after all.
   */
  private PatternConstruct parseUnion(
      int unionStartIndex, int unionFlags, int captureConstructIndex, String captureName) {
    Sequence sequence = new Sequence(index);
    // Lazily built: null until a second alternative is seen. `firstAlternative` holds the first
    // alternative (already unwrapped to its bare construct if it was a single-element sequence) so
    // that a pattern with exactly one "|" still only allocates the union once, right when the
    // second alternative actually shows up (or at the end, if there was exactly one "|" total).
    PatternConstruct firstAlternative = null;
    QuantifiedUnion union = null;
    int rawTextStartIndex = -1;
    // While true, the run accumulating since rawTextStartIndex is a byte-for-byte copy of
    // `pattern` in that span -- no escape has been decoded into it, and no COMMENTS-mode
    // whitespace/comment has been silently skipped in the middle of it (skipComments() runs at
    // the top of every loop iteration) -- so its content can be read straight off `pattern` via a
    // zero-copy java.nio.CharBuffer view (CharBuffer.wrap) instead of ever touching `rawText`:
    // java.lang.String#subSequence just calls substring() internally, so it wouldn't actually
    // save the copy, but CharBuffer.wrap genuinely doesn't copy (see allocation sampling in
    // benchmarks/Intel-i7-9750H_llkCompile_alloc_sampling.txt, where this literal-text handling
    // showed up disproportionately). The instant a run stops being pure (an escape decodes, or a
    // gap opens up), whatever pure prefix had accumulated (rawTextStartIndex..rawTextPureEnd) is
    // copied into `rawText` once, and the run falls back to the old explicit per-character
    // accumulation from there.
    boolean rawTextIsPure = true;
    int rawTextPureEnd = -1; // meaningful only while rawTextIsPure && rawTextStartIndex >= 0
    // Lazy, not eagerly `new`-allocated: with the pure-span handling above, most literal runs in
    // practice (plain ASCII, no escapes, no COMMENTS-mode gaps) never touch `rawText` at all now,
    // so allocating one unconditionally on every parseUnion() call -- this runs once per union
    // level, i.e. often -- was pure waste (StringBuilder.<init> itself showed up as ~4.5% of
    // compile-time CPU in sampling). Reused across every impure run within this one parseUnion
    // call once it does exist, via setLength(0) at each flush site below (not re-nulled).
    StringBuilder rawText = null;
    for (; ; ) {
      skipComments();
      // A plain == chain instead of a "()[]|.^$\0".indexOf(peek) string scan -- this runs once per
      // character of every pattern compiled, and showed up in Android CPU sampling; a chain of int
      // comparisons should be cheaper than a method call into String's own indexOf loop, though (per
      // the project owner's own hedge) the JIT may already optimize the short constant-string scan
      // well enough that this makes no measurable difference -- kept for its own sake regardless,
      // since it's no less readable.
      // ']' is deliberately NOT one of these -- outside a bracket expression it has no special
      // meaning at all, and falls through to the ordinary-character path below (same as
      // java.util.regex, which reads an unmatched ']' as a literal); see
      // remaining_work.md's former entry on this.
      if (peek == '(' || peek == ')' || peek == '[' || peek == '|' || peek == '.'
          || peek == '^' || peek == '$' || peek == EOF) {
        if (rawTextStartIndex >= 0) {
          // rawTextPureEnd, not `index`: this iteration's own skipComments() call just above may
          // already have skipped a trailing comment/whitespace gap since the pure content last
          // ended (e.g. a literal immediately followed by "# comment" to end of pattern) -- `index`
          // now sits past that gap, which must not silently become part of the matched literal.
          CharSequence literalValue = rawTextIsPure
              ? CharBuffer.wrap(pattern, rawTextStartIndex, rawTextPureEnd)
              : rawText.toString();
          LiteralString literal = new LiteralString(rawTextStartIndex, index, literalValue);
          literal.flags = flags;
          sequence.patterns.add(literal);
          if (rawText != null) {
            rawText.setLength(0);
          }
          rawTextStartIndex = -1;
          rawTextIsPure = true;
        }
        switch (peek) {
          case '(':
            // Usually non-null; only a lookbehind whose trailing quantifier resolves to a
            // "0 times" bound (see keepZeroWidthAfterQuantifier, used the same way for \b/\B/^/$)
            // elides itself entirely, same as those other zero-width constructs do.
            PatternConstruct group = parseGroup();
            if (group != null) {
              sequence.patterns.add(group);
            }
            break;
          case '[':
            sequence.patterns.add(parseQuantifiable(parseComplexCharacter()));
            break;
          case '|':
            if (sequence.patterns.isEmpty()) {
              throw throwEmptySequence(sequence.startIndex, unionStartIndex);
            }
            PatternConstruct altConstruct;
            if (sequence.patterns.size() == 1) {
              altConstruct = sequence.patterns.get(0);
            } else {
              sequence.endIndex = index;
              altConstruct = sequence;
            }
            if (union != null) {
              union.constructs.add(altConstruct);
            } else if (firstAlternative == null) {
              firstAlternative = altConstruct;
            } else {
              // Second alternative found -- only now is a real union structurally required.
              union = new QuantifiedUnion(pattern, unionStartIndex);
              union.flags = unionFlags;
              union.captureConstructIndex = captureConstructIndex;
              union.captureName = captureName;
              union.constructs.add(firstAlternative);
              union.constructs.add(altConstruct);
            }
            sequence = new Sequence(index);
            advance(1);
            break;
          case '.':
            // Bug fix (2026-09-06): this unconditionally built "everything" (complement of the
            // empty set), i.e. always behaved as if DOTALL were on -- the DOTALL flag constant
            // existed (Ll1Pattern.DOTALL) but nothing anywhere ever actually consulted it. Without
            // DOTALL, "." must exclude the line terminators (only '\n' under UNIX_LINES) -- see
            // NamedCharClass.RegexCharacterClass.DOT/DOT_UNIX_LINES,
            // reused directly below, always as "any character" regardless of
            // UNICODE_CHARACTER_CLASS -- "." matching only ASCII by default would be wrong, unlike
            // a POSIX/Unicode-property class like \s or \w where that split is exactly the point.
            ComplexCharacter dot;
            if ((flags & Pattern.DOTALL) != 0) {
              // "Everything" is exactly an inverted set with no explicit (excluded) entries -- see
              // CodePointSet#invert's doc for why that's always a finite, valid set here rather
              // than the mathematically-unbounded RangeSet Guava's complement() used to produce.
              MutableCodePointSet everything = new ArrayCodePointSet();
              everything.invert();
              dot = new ComplexCharacter(index, everything);
            } else {
              // Aliased directly, not copied: ComplexCharacter.ranges is effectively immutable
              // once constructed (see its own doc) -- nothing past this point ever mutates it, so
              // there's no risk of corrupting the shared RegexCharacterClass.DOT.unicode instance,
              // and no allocation is needed at all (unlike rebuilding "\n"'s complement fresh).
              dot = new ComplexCharacter(index, ((flags & Pattern.UNIX_LINES) != 0
                  ? RegexCharacterClass.DOT_UNIX_LINES : RegexCharacterClass.DOT).unicode);
            }
            dot.flags = flags;
            // Bug fix (2026-09-07): parseQuantifiable(dot) used to be called BEFORE this advance(1),
            // so it checked for a quantifier suffix (?/*/+/{n,m}) while `peek` was still '.' itself --
            // never seeing the real following character, so "." was silently never quantifiable at
            // all: ".*z" parsed as an unquantified "." followed by the literal text "*z", not "any
            // number of any characters then z". Every other quantifiable construct (bracket classes,
            // plain literals) already advances past its own token before checking for a quantifier --
            // "." is the one construct that didn't. Found while triaging the scraped-corpus harness's
            // un-triaged UNEXPECTED rows (several ".*"/".+" rows turned out to be this, not a genuine
            // behavior divergence). Fixed by advancing first, matching every other call site's
            // convention.
            advance(1);
            sequence.patterns.add(parseQuantifiable(dot));
            break;
          case '^':
            LineBoundaryConstruct lineBegin = new LineBoundaryConstruct(index, index+1, /* isLineBegin= */ true);
            lineBegin.flags = flags;
            advance(1);
            if (keepZeroWidthAfterQuantifier()) {
              sequence.patterns.add(lineBegin);
            }
            break;
          case '$':
            LineBoundaryConstruct lineEnd = new LineBoundaryConstruct(index, index+1, /* isLineBegin= */ false);
            lineEnd.flags = flags;
            advance(1);
            if (keepZeroWidthAfterQuantifier()) {
              sequence.patterns.add(lineEnd);
            }
            break;
          case ')':
          case EOF:
            if (sequence.patterns.isEmpty()) {
              throw throwEmptySequence(sequence.startIndex, unionStartIndex);
            }
            sequence.endIndex = index;
            if (union != null) {
              union.constructs.add(sequence);
              union.endIndex = index;
              return union;
            }
            if (firstAlternative != null) {
              // Exactly one "|" was seen overall -- two alternatives total, so a real union is
              // required, but only now (at the end) is that finally known.
              QuantifiedUnion twoBranch = new QuantifiedUnion(pattern, unionStartIndex);
              twoBranch.flags = unionFlags;
              twoBranch.captureConstructIndex = captureConstructIndex;
              twoBranch.captureName = captureName;
              twoBranch.constructs.add(firstAlternative);
              twoBranch.constructs.add(sequence);
              twoBranch.endIndex = index;
              return twoBranch;
            }
            if (captureConstructIndex != -1) {
              // No "|" at all, but the caller needs a real QuantifiedUnion regardless (a capturing
              // group) to carry its capture index.
              QuantifiedUnion singleBranch = new QuantifiedUnion(pattern, unionStartIndex);
              singleBranch.captureConstructIndex = captureConstructIndex;
              singleBranch.captureName = captureName;
              singleBranch.constructs.add(sequence);
              singleBranch.endIndex = index;
              return singleBranch;
            }
            // No "|", non-capturing: the bare Sequence is the whole result -- no QuantifiedUnion
            // needed at all, deferring that allocation to the caller in case it needs one later
            // (quantifyGroupBody) or not at all (the common case).
            return sequence;
        }
      } else if (peek == '\\') {
        int startIndex = index;
        int codePoint = tryParseSingleCharEscape();
        if (codePoint != -1) {
          skipComments();
          if (peek == '{' || peek == '?' || peek == '+' || peek == '*') {
            // A quantifier belongs to this one escaped character, not to the literal run before it
            // -- same handling as an unescaped character followed by a quantifier, below.
            boolean hasPendingLiteral = rawTextStartIndex >= 0
                && (rawTextIsPure ? rawTextPureEnd > rawTextStartIndex : rawText.length() > 0);
            if (hasPendingLiteral) {
              CharSequence literalValue = rawTextIsPure
                  ? CharBuffer.wrap(pattern, rawTextStartIndex, rawTextPureEnd)
                  : rawText.toString();
              LiteralString literal = new LiteralString(rawTextStartIndex, startIndex, literalValue);
              literal.flags = flags;
              sequence.patterns.add(literal);
              if (rawText != null) {
                rawText.setLength(0);
              }
            }
            ComplexCharacter complex = singleCharacter(startIndex, codePoint);
            complex.flags = flags;
            complex.endIndex = index;
            sequence.patterns.add(parseQuantifiable(complex));
            rawTextStartIndex = -1;
            rawTextIsPure = true;
            continue;
          }
          if (rawTextStartIndex < 0) {
            rawTextStartIndex = startIndex;
          } else if (rawTextIsPure) {
            // Back-fill the pure prefix seen so far (excluding any gap before it -- see
            // rawTextPureEnd's own doc) before switching to explicit accumulation: an escape's
            // decoded content never equals its own raw source text, so this run can't stay a pure
            // view of `pattern` from here on.
            rawText = ensureRawText(rawText, rawTextPureEnd - rawTextStartIndex, startIndex);
            rawText.append(pattern, rawTextStartIndex, rawTextPureEnd);
          }
          rawTextIsPure = false;
          rawText = ensureRawText(rawText, 0, startIndex);
          appendCodePoint(rawText, codePoint);
        } else {
          if (rawTextStartIndex >= 0) {
            // rawTextPureEnd, not `index` -- same reasoning as the top-of-loop flush above.
            CharSequence literalValue = rawTextIsPure
                ? CharBuffer.wrap(pattern, rawTextStartIndex, rawTextPureEnd)
                : rawText.toString();
            LiteralString literal = new LiteralString(rawTextStartIndex, index, literalValue);
            literal.flags = flags;
            sequence.patterns.add(literal);
            if (rawText != null) {
              rawText.setLength(0);
            }
            rawTextStartIndex = -1;
            rawTextIsPure = true;
          }
          if (peek == '\\' && index + 1 < pattern.length() && pattern.charAt(index + 1) == 'G') {
            // \G doesn't match any specific position in the input, so it gets no PatternConstruct
            // at all -- see anchorsToPreviousMatchEnd's doc. It's only meaningful as the very
            // first thing in the whole pattern (i.e. this backslash must be at raw index 0);
            // anywhere else it's ambiguous/pointless (find() has already committed to some other
            // start-position semantics by the time anything else has been parsed) and this engine
            // rejects it outright rather than silently doing nothing there like java.util.regex
            // would, consistent with the project's general stance on unsatisfiable constructs.
            if (index != 0) {
              throw throwUnexpectedChar(
                  "\\G is only allowed as the very first thing in the pattern -- it doesn't "
                      + "match a position in the input, it just anchors find() to exactly where "
                      + "the previous match ended (rather than scanning forward for one)");
            }
            advance(2);
            anchorsToPreviousMatchEnd = true;
            continue;
          }
          PatternConstruct backReference = tryParseBackReference();
          if (backReference != null) {
            sequence.patterns.add(quantifyBackReference(backReference));
            continue;
          }
          if (peek == '\\' && index + 1 < pattern.length() && pattern.charAt(index + 1) == 'X') {
            int graphemeStartIndex = index;
            advance(2);
            GraphemeClusterConstruct graphemeCluster =
                new GraphemeClusterConstruct(graphemeStartIndex, index);
            graphemeCluster.flags = flags;
            sequence.patterns.add(quantifySingleConstruct(graphemeCluster));
            continue;
          }
          PatternConstruct boundaryConstruct = tryParseBoundary();
          if (boundaryConstruct != null) {
            if (keepZeroWidthAfterQuantifier()) {
              sequence.patterns.add(boundaryConstruct);
            }
          } else {
            // parseComplexEscape()'s result is assigned straight into ComplexCharacter.ranges (now
            // effectively immutable -- see its own doc), no defensive copy needed: unlike the
            // bracket-expression '\\' case above (which merges into an already-accumulating
            // ranges local via putAll), this escape is the construct's entire content.
            int escapeStartIndex = index;
            CodePointSet escapeRanges = parseComplexEscape(); // advances past the escape
            ComplexCharacter escapeChar = new ComplexCharacter(escapeStartIndex, index, escapeRanges);
            escapeChar.flags = flags;
            sequence.patterns.add(parseQuantifiable(escapeChar));
          }
        }
      } else {
        int startIndex = index;
        if (peek == '*' || peek == '+' || peek == '?' || peek == '{') {
          // Any quantifier that follows an atom is consumed by parseQuantifiable, so one seen here
          // has nothing to repeat: at the start of a sequence ("*a", "a|+b", "(?i)?a") or after an
          // already-quantified atom ("a**"). java.util.regex rejects these as well.
          throw throwUnexpectedChar(
              " quantifier with nothing to repeat. Did you mean to escape it with a backslash, or to put "
                  + "it after the character, group or class it should repeat?");
        }
        if (rawTextStartIndex < 0) {
          rawTextStartIndex = startIndex;
          rawTextPureEnd = startIndex;
        } else if (rawTextIsPure && startIndex != rawTextPureEnd) {
          // A COMMENTS-mode whitespace/comment run was skipped (skipComments() at the top of this
          // loop) since the pure prefix last ended -- that gap must not silently become part of
          // the matched literal, so back-fill the verbatim prefix seen so far and fall back to
          // explicit accumulation, same as an escape does above.
          rawText = ensureRawText(rawText, rawTextPureEnd - rawTextStartIndex, startIndex);
          rawText.append(pattern, rawTextStartIndex, rawTextPureEnd);
          rawTextIsPure = false;
        }
        int fullChar = Character.codePointAt(patternChars, index);
        advanceCodePoint();
        // fullChar's own characters end here -- captured before the lookahead skipComments()
        // just below, which is about to move `index` past any whitespace/comment that follows
        // (needed to see a quantifier suffix on the far side of one). If that lookahead does skip
        // something, rawTextPureEnd must stay at this pre-skip position, not wherever `index` ends
        // up, or the next char's own gap check (above) would never see the gap: it would find
        // `index` already sitting right where that next char starts, as if nothing were skipped.
        int afterFullChar = index;
        // Under COMMENTS, a quantifier suffix can be separated from its atom by whitespace/a
        // comment ("a * b" means "a*b") -- skip past any before checking for one, same as
        // parseQuantifiable does for every other atom type (bracket classes, groups, ".").
        skipComments();
        if (peek == '{' || peek == '?' || peek == '+' || peek == '*') {
          // Unlike the other two flush sites, rawTextStartIndex alone isn't enough here: this
          // very character may have just opened the run (rawTextStartIndex == rawTextPureEnd,
          // nothing pure actually accumulated yet -- it's about to become its own quantified
          // ComplexCharacter instead, never joining a literal at all) -- rawText.length() alone
          // isn't enough either, symmetrically, since a pure run never touches rawText until it
          // stops being pure. Must check whichever of the two actually holds this run's content.
          boolean hasPendingLiteral = rawTextIsPure
              ? rawTextPureEnd > rawTextStartIndex
              : rawText.length() > 0;
          if (hasPendingLiteral) {
            CharSequence literalValue = rawTextIsPure
                ? CharBuffer.wrap(pattern, rawTextStartIndex, rawTextPureEnd)
                : rawText.toString();
            LiteralString literal = new LiteralString(rawTextStartIndex, index, literalValue);
            literal.flags = flags;
            sequence.patterns.add(literal);
            if (rawText != null) {
              rawText.setLength(0);
            }
          }
          ComplexCharacter complex = singleCharacter(startIndex, fullChar);
          complex.flags = flags;
          complex.endIndex = index;
          sequence.patterns.add(parseQuantifiable(complex));
          rawTextStartIndex = -1;
          rawTextIsPure = true;
        } else if (rawTextIsPure) {
          rawTextPureEnd = afterFullChar;
        } else {
          appendCodePoint(rawText, fullChar);
        }
      }
    }
  }

  // StringBuilder.appendCodePoint's own JDK implementation calls Character.toChars(codePoint) for
  // any supplementary (non-BMP) code point, allocating a throwaway char[2] just to copy its two
  // chars into sb right after -- a real cost here, since rawText.appendCodePoint() runs once per
  // ordinary literal character while parsing (see allocation sampling in
  // benchmarks/Intel-i7-9750H_llkCompile_alloc_sampling.txt). Character.highSurrogate/lowSurrogate
  // compute the same two chars with no allocation, so use those directly instead.
  private static void appendCodePoint(StringBuilder sb, int codePoint) {
    if (Character.isBmpCodePoint(codePoint)) {
      sb.append((char) codePoint);
    } else {
      sb.append(Character.highSurrogate(codePoint)).append(Character.lowSurrogate(codePoint));
    }
  }

  // The chars that always end a literal run outright, whether encountered directly (the top-level
  // delimiter check) or as a quantifier suffix on the run's last atom (checked separately, since a
  // quantifier belongs only to that one atom, not the whole run) -- used only to size `rawText`'s
  // initial capacity below, so approximate is fine. An escape inside the scanned span (e.g. "\n")
  // decodes to fewer chars than its own raw source, so this can only over-estimate, never
  // under-estimate -- also fine for a capacity hint, whose only job is dodging StringBuilder's own
  // default-capacity regrowth (the OTHER allocation this run's sampling flagged, alongside
  // StringBuilder.<init> itself -- see rawText's own doc).
  private static final String LITERAL_RUN_DELIMITERS = "(){}[]|.^$?+*";

  /** How many chars remain in {@code pattern} from {@code fromIndex} up to (not including) the
   *  next character that would end a literal run -- an upper bound on how much more `rawText`
   *  might still need to hold for the run resuming at {@code fromIndex}, per {@link
   *  #LITERAL_RUN_DELIMITERS}'s own doc. */
  private int literalRunCapacityHint(int fromIndex) {
    int len = pattern.length();
    int i = fromIndex;
    while (i < len && LITERAL_RUN_DELIMITERS.indexOf(pattern.charAt(i)) < 0) {
      i++;
    }
    return i - fromIndex;
  }

  /** Lazily creates (or reuses) `rawText`, sized for {@code pureCharsCarriedOver} (a pure prefix
   *  about to be back-filled into it, if any) plus a capacity hint for the rest of the run
   *  resuming at {@code fromIndex} -- see {@link #literalRunCapacityHint}. */
  private StringBuilder ensureRawText(
      @Nullable StringBuilder rawText, int pureCharsCarriedOver, int fromIndex) {
    return rawText != null
        ? rawText
        : new StringBuilder(pureCharsCarriedOver + literalRunCapacityHint(fromIndex));
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

  private @Nullable PatternConstruct parseGroup() {
    if (peek != '(') {
      throw new IllegalStateException("entered parseGroup at illegal start point");
    }
    int groupStartIndex = index;
    int entryFlags = flags;
    advance(1);
    // Mirrors QuantifiedUnion.captureConstructIndex's own default (0, meaning "wants a real index,
    // not yet assigned") until parseUnion() actually needs to be told which one -- kept as locals
    // here (rather than pre-allocating the union itself) so a non-capturing, unquantified,
    // single-alternative group -- by far the common case for "(?:...)" -- never allocates a
    // QuantifiedUnion at all.
    int groupCaptureIndex = 0;
    String captureName = "";
    boolean restoreFlagsOnExit = false;
    if (peek == '?') {
      advance(1);
      switch (peek) {
        case '<':
          advance(1);
          if (peek == '=' || peek == '!') {
            return parseLookbehind(groupStartIndex, /* isPositive= */ peek == '=');
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
          captureName = pattern.substring(startName, index);
          advance(1);
          break;
        case ':':
        case '>':
          // "(?>X)" (atomic group) is just "(?:X)": with no backtracking, every group already
          // matches atomically.
          groupCaptureIndex = -1;
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
        case '-': // negative-only flags, e.g. "(?-i)"
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
          groupCaptureIndex = -1;
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
                  new CodePoint(peek),
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
                    new CodePoint(peek),
                    "\" at the same time.");
              }
              if ((disableFlags & flagValue) != 0) {
                throw throwUnexpectedChar(
                    "It doesn't make sense for a group to disable the same flag \"",
                    new CodePoint(peek),
                    "\" multiple times.");
              }
              disableFlags |= flagValues[flagIdx];
              advance(1);
            }
          }
          // UNICODE_CHARACTER_CLASS implies UNICODE_CASE, on and off, as in java.util.regex.
          if ((enableFlags & Pattern.UNICODE_CHARACTER_CLASS) != 0) {
            enableFlags |= Pattern.UNICODE_CASE;
          }
          if ((disableFlags & Pattern.UNICODE_CHARACTER_CLASS) != 0) {
            disableFlags |= Pattern.UNICODE_CASE;
          }
          if (peek == ')') {
            // A flags-only construct ("(?i)") isn't itself quantifiable and has no body to parse --
            // still needs a real (empty) QuantifiedUnion, since that's the shape #parse()'s caller
            // (an ordinary sequence element) expects back.
            QuantifiedUnion emptyUnion = new QuantifiedUnion(pattern, groupStartIndex);
            emptyUnion.captureConstructIndex = -1;
            emptyUnion.endIndex = index;
            advance(1);
            flags = (flags | enableFlags) & ~disableFlags;
            return emptyUnion;
          } else if (peek == ':') {
            advance(1);
            flags = (flags | enableFlags) & ~disableFlags;
            restoreFlagsOnExit = true;
          } else {
            throw throwUnexpectedChar("That character is illegal in group special construct.");
          }
          break;
        default:
          throw throwUnexpectedChar("Not a valid group special construct for a capture group.");
      }
    }
    // Bug fix (2026-09-07): captureConstructIndex used to be assigned AFTER parseUnion(union)
    // returned, i.e. in closing-paren order -- but this is a recursive-descent parser, so nested
    // groups' own parseGroup() calls (and thus their OWN index assignment) always complete before
    // the call returns here for the OUTER group, meaning an outer group's index always ended up
    // HIGHER than any of its nested groups' indices, backwards from every other regex engine's
    // (and this engine's own group(int)/start(int)/end(int) numbering contract's) "outer group
    // opened first, gets the lower number" convention -- e.g. "(a(b)(c))" assigned group 1="b",
    // group 2="c", group 3="a(b)(c)" instead of the expected 1="a(b)(c)", 2="b", 3="c". Assigning
    // the index here instead, right after the "(" / "(?...)" prefix is parsed and BEFORE
    // recursing into the group's own content, fixes this: index assignment now happens in
    // opening-paren order, exactly matching every other capturing-group numbering convention.
    // Named-group registration moves alongside it for the same reason. `closedGroupsByIndex`
    // (used by backreferences to detect forward references) still only gets populated once the
    // group is fully closed, below -- unaffected by this change, and still correctly rejects a
    // backreference to a group that hasn't closed yet, including a self-reference.
    if (groupCaptureIndex != -1) {
      groupCaptureIndex = captureConstructIndex++;
      if (!captureName.isEmpty()) {
        namedGroups.put(captureName, groupCaptureIndex);
      }
    }
    PatternConstruct body = parseUnion(groupStartIndex, entryFlags, groupCaptureIndex, captureName);
    if (index == pattern.length()) {
      throw throwUnexpectedChar(
          "expected \")\" to match ", new CodePointReference(groupStartIndex));
    }
    if (peek != ')') {
      throw new IllegalStateException("compileBody returned but not at end of the group");
    }
    body.endIndex = index;
    if (groupCaptureIndex != -1) {
      // parseUnion() guarantees a real QuantifiedUnion whenever captureConstructIndex != -1 -- see
      // its own doc.
      closedGroupsByIndex.put(groupCaptureIndex, (QuantifiedUnion) body);
    }
    advance(1);
    PatternConstruct group = quantifyGroupBody(body, groupStartIndex, entryFlags);
    if (restoreFlagsOnExit) {
      flags = entryFlags;
    }
    return group;
  }

  /**
   * Applies a trailing quantifier (if any) to a just-closed group's body. If {@code body} is
   * already a {@code QuantifiedUnion} (a capturing group, or a non-capturing group with more than
   * one alternative -- either way, {@link #parseUnion} already had to allocate one), the quantifier
   * is applied to it directly, exactly as it always was. Otherwise {@code body} is a bare {@code
   * Sequence} (a non-capturing, single-alternative group, whose wrapping union {@link #parseUnion}
   * deferred) -- wrapped in a fresh one-branch {@code QuantifiedUnion} only if a quantifier actually
   * follows, the same wrap-and-check idiom {@link #quantifySingleConstruct} uses for a
   * backreference or {@code \X}.
   */
  private PatternConstruct quantifyGroupBody(
      PatternConstruct body, int groupStartIndex, int groupFlags) {
    if (body instanceof QuantifiedUnion) {
      parseQuantifiable((QuantifiedUnion) body);
      return body;
    }
    skipComments();
    if (peek != '?' && peek != '*' && peek != '+' && peek != '{') {
      return body;
    }
    QuantifiedUnion wrapper = new QuantifiedUnion(pattern, groupStartIndex);
    wrapper.captureConstructIndex = -1;
    wrapper.constructs.add(body);
    wrapper.endIndex = body.endIndex;
    parseQuantifiable(wrapper);
    return wrapper.isUnquantified() ? body : wrapper;
  }

  /**
   * {@code (?<=X)}/{@code (?<!X)}, entered right after the {@code '='}/{@code '!'} has been peeked
   * (not yet consumed) -- see design.md's "Boundary matching" section for why only a body that
   * ALWAYS matches exactly one code point is supported (a direct generalization of {@code \b}/
   * {@code \B}'s own single-code-point {@code peekPrevious()} check; anything wider can't be
   * evaluated in O(1) per position the way this engine requires). The body is parsed with the exact
   * same machinery an ordinary non-capturing group uses, so a real capturing group nested inside it
   * (e.g. {@code (?<=(a))}) is numbered/registered completely normally -- only afterward is the
   * parsed body statically checked for the one-code-point restriction.
   */
  private @Nullable PatternConstruct parseLookbehind(int startIndex, boolean isPositive) {
    advance(1); // consume '=' or '!'
    PatternConstruct body = parseUnion(index, flags, /* captureConstructIndex= */ -1, /* captureName= */ "");
    if (index == pattern.length()) {
      throw throwUnexpectedChar(
          "expected \")\" to match ", new CodePointReference(startIndex));
    }
    if (peek != ')') {
      throw new IllegalStateException("compileBody returned but not at end of the lookbehind group");
    }
    body.endIndex = index;
    advance(1);
    LookbehindConstruct.SingleCodePointBody resolved =
        LookbehindConstruct.resolveSingleCodePointBody(body);
    if (resolved == null) {
      throw throwUnexpectedChar(
          "lookbehind is only supported when its body always matches exactly one code point (a "
              + "single character or character class, or an alternation of such, optionally "
              + "wrapped in one capturing group around the whole body) -- did you mean a "
              + "single-character class like [ab] instead of \"",
          pattern.substring(startIndex, index),
          "\"?");
    }
    LookbehindConstruct lookbehind = new LookbehindConstruct(
        pattern, startIndex, index, isPositive, resolved.codePoints, resolved.captureConstructIndex);
    lookbehind.flags = flags;
    // Not itself quantifiable in this engine, same as \b/\B/^/$ -- see keepZeroWidthAfterQuantifier's
    // own doc. A "{0}" bound elides it entirely (returns null), same as those other constructs.
    return keepZeroWidthAfterQuantifier() ? lookbehind : null;
  }

  private ComplexCharacter parseComplexCharacter() {
    if (peek != '[') {
      throw new IllegalStateException("entered parseComplexCharacter at illegal start point");
    }
    int startIndex = index;
    CodePointSet finalRanges = parseComplexCharacterRanges(startIndex);
    ComplexCharacter complex = new ComplexCharacter(startIndex, index, finalRanges);
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
    if ((flags & Pattern.CASE_INSENSITIVE) == 0) {
      ranges.add(codePoint, codePoint + 1);
    } else {
      CaseFolding.addSingle(ranges, codePoint, CaseFolding.isUnicodeCase(flags));
    }
  }

  /** {@link #addLiteral} for an explicit {@code lo-hi} range ({@code max} exclusive). */
  private void addLiteralRange(CodePointSetBuilder ranges, int min, int max) {
    if ((flags & Pattern.CASE_INSENSITIVE) == 0) {
      ranges.add(min, max);
    } else {
      CaseFolding.addRange(ranges, min, max, CaseFolding.isUnicodeCase(flags));
    }
  }

  /** A one-code-point {@code ComplexCharacter} (a literal that had to become a class, e.g. to be quantified). */
  private ComplexCharacter singleCharacter(int startIndex, int codePoint) {
    if ((flags & Pattern.CASE_INSENSITIVE) == 0) {
      return new ComplexCharacter(startIndex, codePoint);
    }
    CodePointSetBuilder members = CodePointSetBuilder.create();
    addLiteral(members, codePoint);
    return new ComplexCharacter(startIndex, members.build());
  }

  /**
   * The core of {@link #parseComplexCharacter}: parses one {@code "[" IntersectionCharacter "]"}
   * (already-negated if {@code ^} was present) and returns its finished ranges, advancing {@code
   * index} past the closing {@code "]"}. Split out from {@link #parseComplexCharacter} so a nested
   * class (the {@code '['} case below, e.g. {@code "[a-c[p-z]]"}) can recurse straight into this
   * -- merging the result directly into the enclosing accumulator via {@link #mergeInto} -- rather
   * than building a whole separate {@code ComplexCharacter} object just to immediately discard
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
    // CodePointSetBuilder#add is a plain O(1)-amortized append regardless of order, deferring the
    // sort/coalesce ArrayCodePointSet#add would otherwise do on every single-character member to
    // one #build() call when this operand run is actually finished (at "&&" or the closing "]").
    @Nullable CodePointSet intersectionSoFar = null;
    for (; ; ) {
      switch (peek) {
        case EOF:
          throw throwUnexpectedChar("expected \"]\" to match ", new CodePointReference(startIndex));
        case ']':
          if (index > firstContentIndex) {
            advance(1); // consume the ']' -- callers expect peek to be past this construct
            CodePointSet completedRun = CodePointSetBuilder.mergeRun(ranges, runUnion);
            CodePointSet finalRanges =
                intersectionSoFar == null
                    ? completedRun
                    : intersectionSoFar.intersection(completedRun);
            return negate ? finalRanges.complement() : finalRanges;
          } else {
            ranges.add(+']', +']' + 1);
            advance(1);
            break;
          }
        case '-':
          ranges.add(+'-', +'-' + 1);
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
            CodePointSet completedRun = CodePointSetBuilder.mergeRun(ranges, runUnion);
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

  static private final String META_CHARACTERS = "^.[]$()*{}?+|\\";
  static private final String CONTROL_CODES = "@ABCDEFGHIJKLMNOPQRTSTUVWXYZ[\\]^_";
  private int tryParseSingleCharEscape() {
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
          int digit;
          if (peek >= '0' && peek <= '9') {
            digit = peek - '0';
          } else if (peek >= 'a' && peek <= 'f') {
            digit = 10 + peek - 'a';
          } else if (peek >= 'A' && peek <= 'F') {
            digit = 10 + peek - 'A';
          } else {
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

  private static boolean isQuantifierStart(char afterBrace) {
    return (afterBrace >= '0' && afterBrace <= '9') || afterBrace == ',';
  }

  /** Any {@code \\b{...}}/{@code \\B{...}} boundary-type suffix other than the plain {@code \\b{g}}
   *  already handled by this method's caller (grapheme boundary -- {@code \\B{g}} is NOT special
   *  syntax, matching java.util.regex) isn't supported; without this the "{...}" (a "{" not
   *  starting a quantifier) would silently be read as literal text after a plain word boundary. */
  private void rejectBoundaryType() {
    if (peek == '{' && !(index + 1 < pattern.length() && isQuantifierStart(pattern.charAt(index + 1)))) {
      throw throwUnexpectedChar(
          " boundary type. Only \\b, \\B, and \\b{g} (grapheme boundary) are supported -- "
              + "\\X (extended grapheme cluster) is also supported, just not as a boundary type");
    }
  }

  private @Nullable PatternConstruct tryParseBoundary() {
    if (peek != '\\') {
      throw new IllegalStateException("entered tryParseBoundary at illegal start point");
    }
    int peek2 = peekAfter();
    switch (peek2) {
      case 'b': {
        int startIndex = index;
        advance(2);
        if (peek == '{' && index + 2 < pattern.length()
            && pattern.charAt(index + 1) == 'g' && pattern.charAt(index + 2) == '}') {
          advance(3);
          GraphemeBoundaryConstruct g = new GraphemeBoundaryConstruct(startIndex, index);
          g.flags = flags;
          return g;
        }
        rejectBoundaryType();
        WordBoundaryConstruct b = new WordBoundaryConstruct(pattern, index-2, index, /* isWordBoundary= */ true);
        b.flags = flags;
        return b;
      }
      case 'B': {
        advance(2);
        rejectBoundaryType();
        WordBoundaryConstruct b = new WordBoundaryConstruct(pattern, index-2, index, /* isWordBoundary= */ false);
        b.flags = flags;
        return b;
      }
      case 'A': {
        advance(2);
        BoundaryConstruct b = new BoundaryConstruct(index-2, index, BoundaryEnum.InputBegin);
        b.flags = flags;
        return b;
      }
      case 'Z': {
        advance(2);
        BoundaryConstruct b = new BoundaryConstruct(index-2, index, BoundaryEnum.InputEndExceptTerminator);
        b.flags = flags;
        return b;
      }
      case 'z': {
        advance(2);
        BoundaryConstruct b = new BoundaryConstruct(index-2, index, BoundaryEnum.InputEnd);
        b.flags = flags;
        return b;
      }
    }
    return null;
  }

  /**
   * A backreference isn't itself quantifiable, so one followed by a quantifier (e.g. a numbered
   * reference and '+') is wrapped in a one-branch, non-capturing union; an unquantified one is
   * returned as-is.
   */
  private PatternConstruct quantifyBackReference(PatternConstruct backReference) {
    return quantifySingleConstruct(backReference);
  }

  /**
   * Wraps a construct that isn't itself a {@code QuantifiableConstruct} (a backreference, or
   * {@code \X}) in a one-branch, non-capturing union so a following quantifier (e.g. {@code \X+})
   * has somewhere to attach; returns the construct as-is if nothing follows.
   */
  private PatternConstruct quantifySingleConstruct(PatternConstruct construct) {
    skipComments();
    if (peek != '?' && peek != '*' && peek != '+' && peek != '{') {
      return construct;
    }
    QuantifiedUnion wrapper = new QuantifiedUnion(pattern, construct.startIndex);
    wrapper.captureConstructIndex = -1;
    Sequence body = new Sequence(construct.startIndex);
    body.patterns.add(construct);
    body.endIndex = construct.endIndex;
    wrapper.constructs.add(body);
    wrapper.endIndex = construct.endIndex;
    parseQuantifiable(wrapper);
    return wrapper.isUnquantified() ? construct : wrapper;
  }

  /**
   * {@code \1}-{@code \9} (numbered backreference) or {@code \k<name>} (named backreference), or
   * null if {@code peek}/{@code peek2} don't start either form. Resolves the reference to its
   * already-parsed {@code QuantifiedUnion} immediately (via {@code closedGroupsByIndex}/{@code
   * namedGroups}), rejecting forward references and references to undefined groups here at parse
   * time -- see design.md's "Backreferences" section and {@code closedGroupsByIndex}'s doc.
   *
   * <p>Like {@code java.util.regex}, further digits are consumed greedily only while the resulting
   * number doesn't exceed the number of groups opened so far, so {@code \12} means group 12 once 12
   * groups have been opened, and group 1 followed by a literal "2" otherwise.
   */
  private @Nullable PatternConstruct tryParseBackReference() {
    if (peek != '\\') {
      throw new IllegalStateException("entered tryParseBackReference at illegal start point");
    }
    int peek2 = peekAfter();
    if (peek2 >= '1' && peek2 <= '9') {
      int startIndex = index;
      int groupNumber = peek2 - '0';
      int digitsEnd = index + 2;
      while (digitsEnd < pattern.length()) {
        char digit = pattern.charAt(digitsEnd);
        int extended = groupNumber * 10 + (digit - '0');
        if (digit < '0' || digit > '9' || extended > captureConstructIndex) {
          break;
        }
        groupNumber = extended;
        digitsEnd++;
      }
      int referencedIndex = groupNumber - 1;
      QuantifiedUnion referenced = closedGroupsByIndex.get(referencedIndex);
      if (referenced == null) {
        throw PatternSyntaxException.throwWithReferences(
            pattern,
            startIndex,
            "backreference \\", groupNumber, " refers to a group that either doesn't exist or ",
            "hasn't been closed yet at this point in the pattern (forward references aren't ",
            "supported) -- ", captureConstructIndex, " capturing group(s) defined so far");
      }
      advance(digitsEnd - index);
      BackReference backReference = new BackReference(startIndex, index, referencedIndex, referenced);
      backReference.flags = flags;
      return backReference;
    }
    if (peek2 == 'k') {
      int startIndex = index;
      advance(2);
      if (peek != '<') {
        throw throwUnexpectedChar("\\k must be followed by \"<name>\" naming a capturing group");
      }
      advance(1);
      int startName = index;
      while ((peek >= '0' && peek <= '9')
          || (peek >= 'a' && peek <= 'z')
          || (peek >= 'A' && peek <= 'Z')) {
        advance(1);
      }
      if (peek != '>') {
        throw throwUnexpectedChar(
            "Character not allowed in backreference name. Expected '>' to match ",
            new CodePointReference(startName));
      }
      String name = pattern.substring(startName, index);
      advance(1);
      Integer referencedIndex = namedGroups.get(name);
      if (referencedIndex == null) {
        throw PatternSyntaxException.throwWithReferences(
            pattern,
            startIndex,
            "backreference \\k<", name, "> refers to a named group that either doesn't exist or ",
            "hasn't been closed yet at this point in the pattern (forward references aren't ",
            "supported)");
      }
      QuantifiedUnion referenced = closedGroupsByIndex.get(referencedIndex);
      BackReference backReference = new BackReference(startIndex, index, referencedIndex, referenced);
      backReference.flags = flags;
      return backReference;
    }
    return null;
  }

  /**
   * Parses the escape at {@code peek} ({@code \d}, {@code \p{...}}, etc. -- not a single-char
   * escape like {@code \n}, which {@link #tryParseSingleCharEscape} already handles before a
   * caller ever reaches here), advancing past it, and returns the {@link CodePointSet} it denotes.
   *
   * <p>A pure function rather than one that mutates a {@code ComplexCharacter}/{@code ranges}
   * parameter in place: every result here is either a {@code NamedCharClass}/{@code
   * RegexCharacterClass} static constant or a freshly built complement of one, both safe to hand
   * back directly. A caller building a standalone {@code ComplexCharacter} for just this escape
   * (the common case -- a bare {@code \d} in running pattern text) can then assign the result
   * straight into that construct's (effectively immutable) {@code ranges} with no defensive copy;
   * a caller merging this into an already-accumulating bracket-expression {@code ranges} (e.g.
   * {@code [a\d]}) still goes through {@code putAll} itself, same as merging any other member.
   */
  private CodePointSet parseComplexEscape() {
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
    NamedCharClass.CharacterClassPrefix prefix;
    if (charClassName.startsWith("Is")) {
      prefix = NamedCharClass.CharacterClassPrefix.is;
      charClassName = charClassName.substring(2);
    } else if (charClassName.startsWith("In")) {
      prefix = NamedCharClass.CharacterClassPrefix.in;
      charClassName = charClassName.substring(2);
    } else if (charClassName.startsWith("java")) {
      prefix = NamedCharClass.CharacterClassPrefix.java;
    } else if (charClassName.startsWith("script=") || charClassName.startsWith("sc=")) {
      prefix = NamedCharClass.CharacterClassPrefix.script;
      charClassName = charClassName.substring(charClassName.indexOf('=') + 1);
    } else if (charClassName.startsWith("block=") || charClassName.startsWith("blk=")) {
      prefix = NamedCharClass.CharacterClassPrefix.block;
      charClassName = charClassName.substring(charClassName.indexOf('=') + 1);
    } else if (charClassName.startsWith("general_category=") || charClassName.startsWith("gc=")) {
      prefix = NamedCharClass.CharacterClassPrefix.general_category;
      charClassName = charClassName.substring(charClassName.indexOf('=') + 1);
    } else if (charClassName.indexOf('=') < 0) {
      prefix = NamedCharClass.CharacterClassPrefix.none;
    } else {
      throw throwUnexpectedChar(
          "unknown Unicode prefix in character class \"", charClassName, "\"");
    }

    // \p{script=Latin} always means a script; \p{IsXxx} tries the named classes/binary properties
    // first and falls back to a script (\p{IsLatin}), same order as java.util.regex.
    if (prefix == NamedCharClass.CharacterClassPrefix.script
        || prefix == NamedCharClass.CharacterClassPrefix.is
            && !NamedCharClass.isNamedClass(charClassName)) {
      CodePointSet scriptRanges = NamedCharClass.scriptByName(charClassName);
      if (scriptRanges == null) {
        throw throwUnexpectedChar("unknown named character class \"", originalCharClassName, "\"");
      }
      return positive ? scriptRanges : scriptRanges.complement();
    }

    // \p{InGreek}/\p{block=Greek} are always blocks.
    if (prefix == NamedCharClass.CharacterClassPrefix.in
        || prefix == NamedCharClass.CharacterClassPrefix.block) {
      CodePointSet blockRanges = NamedCharClass.blockByName(charClassName);
      if (blockRanges == null) {
        throw throwUnexpectedChar("unknown named character class \"", originalCharClassName, "\"");
      }
      return positive ? blockRanges : blockRanges.complement();
    }

    try {
      NamedCharClass namedClass = prefix == NamedCharClass.CharacterClassPrefix.is
          ? NamedCharClass.valueOfIs(charClassName)
          : NamedCharClass.valueOf(charClassName);
      CodePointSet namedRanges = namedClass.get(prefix, flags);
      if ((flags & Pattern.CASE_INSENSITIVE) != 0) {
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
      ranges.add(+'-', +'-' + 1);
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

  private PatternConstruct parseQuantifiable(ComplexCharacter construct) {
    // construct.flags is already set by whoever built it (every ComplexCharacter creation site
    // sets it directly, since it also needs the correct value for the never-quantified case, which
    // never reaches here at all).
    //
    // skipComments() first, then peek for an actual quantifier suffix, so the overwhelmingly
    // common unquantified case (a lone bracket class/"."/ escape with nothing after it) can return
    // `construct` itself unwrapped instead of always allocating a ComplexQuantifiedCharacter just
    // to immediately discover there's nothing to quantify -- this was PatternParser's #3
    // allocation site by CPU-sampling weight (~6% of Pattern.compile's allocations; see notes.md).
    // Safe to skip the generic parseQuantifiable(T) overload entirely here: when none of
    // '?'/'*'/'+'/'{' follow, that overload's own body is a no-op (its trailing reluctant/
    // possessive check can only see a '?'/'+' here if one of those branches already consumed a
    // real quantifier first).
    skipComments();
    if (peek != '?' && peek != '*' && peek != '+' && peek != '{') {
      return construct;
    }
    return parseQuantifiable(new ComplexQuantifiedCharacter(pattern, index, construct));
  }

  // Character.codePointOf (JDK 9+, and Android from the release whose ICU has it) is the only source of
  // Unicode character names here: embedding the whole name table would cost far more than this rarely
  // used escape is worth, so the running platform's own table is used, looked up reflectively because
  // this module compiles at source level 8.
  private static final class CharacterNames {
    static final java.lang.reflect.Method CODE_POINT_OF;

    static {
      java.lang.reflect.Method method = null;
      try {
        method = Character.class.getMethod("codePointOf", String.class);
      } catch (NoSuchMethodException e) {
        // reported with a detailed message at the point of use
      }
      CODE_POINT_OF = method;
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
    if (CharacterNames.CODE_POINT_OF == null) {
      throw throwUnexpectedChar(
          "\\N{name} needs Character.codePointOf, which this runtime (Java "
              + System.getProperty("java.version") + ") doesn't have. Use \\x{...} with the "
              + "code point instead");
    }
    int codePoint;
    try {
      codePoint = (Integer) CharacterNames.CODE_POINT_OF.invoke(null, name);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw throwUnexpectedChar(
          "Unknown character name \"", name, "\" in \\N{name}. Names are the Unicode names, "
              + "e.g. \"LATIN SMALL LETTER A\"; this runtime's Unicode data may predate a newer "
              + "character");
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
    advance(nameEnd + 1 - index);
    return codePoint;
  }

  /**
   * Consumes a quantifier suffix after a zero-width construct ({@code ^ $  \B \A \Z \z}), as
   * java.util.regex allows. A zero-width assertion is idempotent, so {@code X{min,max}} is just
   * {@code X} when {@code min >= 1} and matches nothing extra when {@code min == 0}. Returns whether
   * the construct itself must still be added to the sequence.
   */
  private boolean keepZeroWidthAfterQuantifier() {
    skipComments();
    if (peek != '?' && peek != '*' && peek != '+' && peek != '{') {
      return true;
    }
    int savedQuantifiableIndex = quantifiableIndex;
    QuantifiableConstruct quantifier = parseQuantifiable(new QuantifiableConstruct(pattern, index) {
      @Override
      void buildEntryMap(PatternConstruct next) {
        throw new UnsupportedOperationException("scratch quantifier holder for a zero-width construct");
      }

      @Override
      void buildMatcher() {
        throw new UnsupportedOperationException("scratch quantifier holder for a zero-width construct");
      }
    });
    quantifiableIndex = savedQuantifiableIndex; // the scratch holder needs no loop counter slot
    return quantifier.min >= 1;
  }

  private <T extends QuantifiableConstruct> T parseQuantifiable(T construct) {
    // Under COMMENTS, whitespace/comments between an atom and its quantifier suffix ("a *") are
    // insignificant too, same as everywhere else outside a character class.
    skipComments();
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
        // Integer.parseInt(CharSequence, int, int, int) (JDK 9+) parses the span directly, no
        // substring() copy needed for a value used once and discarded.
        construct.min = Integer.parseInt(pattern, index, end, 10);
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
            construct.max = Integer.parseInt(pattern, index, end, 10);
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
      // Possessive ('+') is still compiled identically to plain greedy (this engine's no-backtrack
      // greedy loop already behaves as possessive -- nothing to backtrack into), but is recorded on
      // the construct anyway -- see QuantifiableConstruct#possessive -- since the two are no longer
      // interchangeable for the loop/zero-width-assertion ambiguity check buildLoopMatcher runs:
      // possessive genuinely never backtracks in java.util.regex either, so it's exempt from a
      // rejection that greedy syntax needs (see that field's own doc). Reluctant ('?') is recorded
      // on the construct too -- see QuantifiableConstruct#reluctant.
      construct.reluctant = peek == '?';
      construct.possessive = peek == '+';
      advance(1);
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
