package com.tbohne.llkpattern.impl.parser;

import com.tbohne.llkpattern.impl.unicode.UnicodeFlags;
import static com.tbohne.llkpattern.impl.parser.PatternText.appendCodePoint;
import static com.tbohne.llkpattern.impl.parser.PatternText.isAsciiAlphanumeric;
import static com.tbohne.llkpattern.impl.parser.PatternText.isQuantifierChar;
import static com.tbohne.llkpattern.impl.parser.PatternText.startsBracedQuantifier;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import androidx.collection.MutableIntObjectMap;
import androidx.collection.MutableObjectIntMap;
import androidx.collection.ObjectIntMap;
import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.PatternSyntaxException;
import com.tbohne.llkpattern.PatternSyntaxException.CodePoint;
import com.tbohne.llkpattern.PatternSyntaxException.CodePointReference;
import com.tbohne.llkpattern.impl.constructs.*;
import com.tbohne.llkpattern.impl.constructs.BoundaryPatternConstruct.BoundaryEnum;
import java.nio.CharBuffer;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class PatternParser extends CharClassParser {
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
  // androidx.collection.MutableObjectIntMap instead of java.util.HashMap<String, Integer>: no
  // per-entry Integer boxing, and (like closedGroupsByIndex below) a flat open-addressed table
  // instead of a linked Node per entry -- most patterns have zero or one named group, so this is
  // usually either empty or a single small insert, not a data structure worth a java.util.HashMap's
  // per-instance overhead.
  // Shared, empty, never mutated: getNamedGroups() returns this for the common case of a pattern
  // with no named groups at all, instead of allocating a MutableObjectIntMap per compile that
  // would just sit empty. Safe to share across parses because it is read-only from the outside
  // (ObjectIntMap has no mutators) and this class never mutates it either -- see namedGroups below.
  private static final ObjectIntMap<String> EMPTY_NAMED_GROUPS = new MutableObjectIntMap<>(0);

  // Null until the first named group is registered (parseGroup), rather than an eagerly-constructed
  // androidx.collection.MutableObjectIntMap instead of java.util.HashMap<String, Integer>: no
  // per-entry Integer boxing, and (like closedGroupsByIndex below) a flat open-addressed table
  // instead of a linked Node per entry -- but most patterns have zero named groups, so skipping the
  // map object itself (not just its backing arrays) is worth it for the common case.
  private @Nullable MutableObjectIntMap<String> namedGroups;
  // captureConstructIndex -> the already-fully-parsed QuantifiedUnionPatternConstruct for that group, populated at
  // the same point as namedGroups (parseGroup, once a group's ")" is reached). Backreferences
  // (tryParseBackReference) look a referenced group up here: only a group already present -- i.e.
  // already closed, textually before the "\1"/"\k<name>" -- can be referenced; anything else is a
  // forward reference or an undefined group, both rejected at parse time. See design.md's
  // "Backreferences" section.
  //
  // androidx.collection.MutableIntObjectMap instead of java.util.HashMap<Integer, QuantifiedUnionPatternConstruct>:
  // this used to show up at 9.4% of compile-time allocation just for the HashMap itself (every
  // compile() paid for one, whether or not the pattern has any backreferences at all -- see
  // benchmarks/Intel-i7-9750H_llkCompile_alloc_sampling.txt), plus a boxed Integer key and a
  // HashMap.Node per capturing group. A primitive-int-keyed open-addressed map needs neither.
  // Null until the first capturing group closes -- see namedGroups' own doc just above for why
  // (this one only saves the allocation for patterns with no capturing groups at all, since any
  // capturing group populates it regardless of whether a backreference ever uses it).
  private @Nullable MutableIntObjectMap<QuantifiedUnionPatternConstruct> closedGroupsByIndex;

  public PatternParser(String pattern, int flags) {
    super(pattern, flags);
  }

  /** Total number of quantifiable (?, *, +, {n,m}) constructs -- sizes Matcher#quantifiableCounts. */
  public int getQuantifiableCount() {
    return quantifiableIndex;
  }

  /** Total number of capturing groups -- sizes Matcher#captureGroups. */
  public int getCaptureGroupCount() {
    return captureConstructIndex;
  }

  /** Named capturing groups' names mapped to their captureConstructIndex. */
  public ObjectIntMap<String> getNamedGroups() {
    return namedGroups != null ? namedGroups : EMPTY_NAMED_GROUPS;
  }

  /** Whether the pattern used {@code \G} -- see the field's own doc for what that means. */
  public boolean anchorsToPreviousMatchEnd() {
    return anchorsToPreviousMatchEnd;
  }

  public PatternConstruct parse() {
    if ((flags & Pattern.LITERAL) != 0) {
      return parseLiteralPattern();
    }
    // The whole pattern isn't a capturing group -- only parseGroup() should assign a real
    // captureConstructIndex (-1 here, same as parseUnion's non-capturing callers). A QuantifiedUnionPatternConstruct
    // is only actually allocated (by parseUnion) if the pattern has a top-level "|"; a single
    // alternative comes back as a bare SequencePatternConstruct, same shape #parse() has always returned for that
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
   * as in {@code java.util.regex}. Returns the same bare {@code SequencePatternConstruct} shape {@link #parse()}
   * gives any single-alternative pattern.
   */
  private PatternConstruct parseLiteralPattern() {
    if (pattern.isEmpty()) {
      throw throwEmptySequence(0, 0);
    }
    SequencePatternConstruct sequence = new SequencePatternConstruct(0);
    LiteralPatternConstruct literal = new LiteralPatternConstruct(0, pattern.length(), pattern);
    literal.flags = flags;
    sequence.patterns.add(literal);
    sequence.endIndex = pattern.length();
    index = pattern.length();
    return sequence;
  }

  /**
   * Parses one or more {@code '|'}-separated alternatives starting at the current position, up to
   * (not consuming) the matching {@code ')'} or end of pattern. {@code unionStartIndex}/{@code
   * unionFlags} are the position/flags a wrapping {@code QuantifiedUnionPatternConstruct} would have been
   * constructed with had one been pre-allocated by the caller (the old design); {@code
   * captureConstructIndex}/{@code captureName} are that union's capture identity, or -1/"" for a
   * non-capturing caller (a plain group body, a lookbehind body, or the whole pattern).
   *
   * <p>A {@code QuantifiedUnionPatternConstruct} is only actually allocated when one is structurally required: more
   * than one alternative was found, or {@code captureConstructIndex != -1} (a capturing group
   * always needs a real object to carry its index for backreferences/{@code closedGroupsByIndex}).
   * Otherwise (exactly one alternative, non-capturing) the bare {@code SequencePatternConstruct} is returned
   * directly -- the caller (parseGroup's {@code quantifyGroupBody}, or #parse()) is responsible for
   * wrapping it later if it turns out to need quantifying after all.
   */
  private PatternConstruct parseUnion(
      int unionStartIndex, int unionFlags, int captureConstructIndex, String captureName) {
    // The current alternative's contents, built up lazily -- see #addToAlternative's own doc for
    // why this saves an allocation (the SequencePatternConstruct itself, plus its ArrayList) for the very common
    // case of a single-element alternative (before a "|", or the only alternative in a
    // non-capturing group/pattern with none at all): null (empty), a bare PatternConstruct (one
    // element so far), or a real SequencePatternConstruct (2+ elements, already flattened into it).
    Object accumulator = null;
    int altStartIndex = index;
    // Lazily built: null until a second alternative is seen. `firstAlternative` holds the first
    // alternative (already unwrapped to its bare construct if it was a single-element sequence) so
    // that a pattern with exactly one "|" still only allocates the union once, right when the
    // second alternative actually shows up (or at the end, if there was exactly one "|" total).
    PatternConstruct firstAlternative = null;
    QuantifiedUnionPatternConstruct union = null;
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
    @Nullable StringBuilder rawText = null;
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
              : castNonNull(rawText).toString();
          LiteralPatternConstruct literal = new LiteralPatternConstruct(rawTextStartIndex, index, literalValue);
          literal.flags = flags;
          accumulator = addToAlternative(accumulator, literal, altStartIndex);
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
              accumulator = addToAlternative(accumulator, group, altStartIndex);
            }
            break;
          case '[':
            accumulator = addToAlternative(
                accumulator, parseQuantifiable(parseComplexCharacter()), altStartIndex);
            break;
          case '|':
            if (accumulator == null) {
              throw throwEmptySequence(altStartIndex, unionStartIndex);
            }
            PatternConstruct altConstruct;
            if (accumulator instanceof SequencePatternConstruct) {
              ((SequencePatternConstruct) accumulator).endIndex = index;
              altConstruct = (SequencePatternConstruct) accumulator;
            } else {
              altConstruct = (PatternConstruct) accumulator;
            }
            if (union != null) {
              union.constructs.add(altConstruct);
            } else if (firstAlternative == null) {
              firstAlternative = altConstruct;
            } else {
              // Second alternative found -- only now is a real union structurally required.
              union = new QuantifiedUnionPatternConstruct(pattern, unionStartIndex);
              union.flags = unionFlags;
              union.captureConstructIndex = captureConstructIndex;
              union.captureName = captureName;
              union.constructs.add(firstAlternative);
              union.constructs.add(altConstruct);
            }
            accumulator = null;
            altStartIndex = index;
            advance(1);
            break;
          case '.':
            ComplexCharacterPatternConstruct dot = parseDot();
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
            accumulator = addToAlternative(accumulator, parseQuantifiable(dot), altStartIndex);
            break;
          case '^':
            LineBoundaryPatternConstruct lineBegin = new LineBoundaryPatternConstruct(index, index+1, /* isLineBegin= */ true);
            lineBegin.flags = flags;
            advance(1);
            if (keepZeroWidthAfterQuantifier()) {
              accumulator = addToAlternative(accumulator, lineBegin, altStartIndex);
            }
            break;
          case '$':
            LineBoundaryPatternConstruct lineEnd = new LineBoundaryPatternConstruct(index, index+1, /* isLineBegin= */ false);
            lineEnd.flags = flags;
            advance(1);
            if (keepZeroWidthAfterQuantifier()) {
              accumulator = addToAlternative(accumulator, lineEnd, altStartIndex);
            }
            break;
          case ')':
          case EOF:
            if (accumulator == null) {
              throw throwEmptySequence(altStartIndex, unionStartIndex);
            }
            // The final alternative is always kept as a real SequencePatternConstruct, even if it turns out to
            // have just one element -- unlike an alternative before a "|" (see #addToAlternative's
            // doc), this one's shape is a contract other code relies on (see CLAUDE.md's "Parser
            // root shape" note and Ll1Pattern#startsWithBeginAnchor).
            SequencePatternConstruct finalSequence;
            if (accumulator instanceof SequencePatternConstruct) {
              finalSequence = (SequencePatternConstruct) accumulator;
            } else {
              finalSequence = new SequencePatternConstruct(altStartIndex);
              finalSequence.patterns.add((PatternConstruct) accumulator);
            }
            finalSequence.endIndex = index;
            if (union != null) {
              union.constructs.add(finalSequence);
              union.endIndex = index;
              return union;
            }
            if (firstAlternative != null) {
              // Exactly one "|" was seen overall -- two alternatives total, so a real union is
              // required, but only now (at the end) is that finally known.
              QuantifiedUnionPatternConstruct twoBranch = new QuantifiedUnionPatternConstruct(pattern, unionStartIndex);
              twoBranch.flags = unionFlags;
              twoBranch.captureConstructIndex = captureConstructIndex;
              twoBranch.captureName = captureName;
              twoBranch.constructs.add(firstAlternative);
              twoBranch.constructs.add(finalSequence);
              twoBranch.endIndex = index;
              return twoBranch;
            }
            if (captureConstructIndex != -1) {
              // No "|" at all, but the caller needs a real QuantifiedUnionPatternConstruct regardless (a capturing
              // group) to carry its capture index.
              QuantifiedUnionPatternConstruct singleBranch = new QuantifiedUnionPatternConstruct(pattern, unionStartIndex);
              singleBranch.captureConstructIndex = captureConstructIndex;
              singleBranch.captureName = captureName;
              singleBranch.constructs.add(finalSequence);
              singleBranch.endIndex = index;
              return singleBranch;
            }
            // No "|", non-capturing: the bare SequencePatternConstruct is the whole result -- no QuantifiedUnionPatternConstruct
            // needed at all, deferring that allocation to the caller in case it needs one later
            // (quantifyGroupBody) or not at all (the common case).
            return finalSequence;
        }
      } else if (peek == '\\') {
        int startIndex = index;
        int codePoint = tryParseSingleCharEscape();
        if (codePoint != -1) {
          skipComments();
          if (isQuantifierChar(peek)) {
            // A quantifier belongs to this one escaped character, not to the literal run before it
            // -- same handling as an unescaped character followed by a quantifier, below.
            boolean hasPendingLiteral = rawTextStartIndex >= 0
                && (rawTextIsPure ? rawTextPureEnd > rawTextStartIndex : castNonNull(rawText).length() > 0);
            if (hasPendingLiteral) {
              CharSequence literalValue = rawTextIsPure
                  ? CharBuffer.wrap(pattern, rawTextStartIndex, rawTextPureEnd)
                  : castNonNull(rawText).toString();
              LiteralPatternConstruct literal = new LiteralPatternConstruct(rawTextStartIndex, startIndex, literalValue);
              literal.flags = flags;
              accumulator = addToAlternative(accumulator, literal, altStartIndex);
              if (rawText != null) {
                rawText.setLength(0);
              }
            }
            ComplexCharacterPatternConstruct complex = singleCharacter(startIndex, codePoint);
            complex.flags = flags;
            complex.endIndex = index;
            accumulator = addToAlternative(accumulator, parseQuantifiable(complex), altStartIndex);
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
                : castNonNull(rawText).toString();
            LiteralPatternConstruct literal = new LiteralPatternConstruct(rawTextStartIndex, index, literalValue);
            literal.flags = flags;
            accumulator = addToAlternative(accumulator, literal, altStartIndex);
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
            accumulator = addToAlternative(accumulator, quantifyBackReference(backReference), altStartIndex);
            continue;
          }
          if (peek == '\\' && index + 1 < pattern.length() && pattern.charAt(index + 1) == 'X') {
            int graphemeStartIndex = index;
            advance(2);
            GraphemeClusterPatternConstruct graphemeCluster =
                new GraphemeClusterPatternConstruct(graphemeStartIndex, index);
            graphemeCluster.flags = flags;
            accumulator = addToAlternative(accumulator, quantifySingleConstruct(graphemeCluster), altStartIndex);
            continue;
          }
          PatternConstruct boundaryConstruct = tryParseBoundary();
          if (boundaryConstruct != null) {
            if (keepZeroWidthAfterQuantifier()) {
              accumulator = addToAlternative(accumulator, boundaryConstruct, altStartIndex);
            }
          } else {
            // parseComplexEscape()'s result is assigned straight into ComplexCharacterPatternConstruct.ranges (now
            // effectively immutable -- see its own doc), no defensive copy needed: unlike the
            // bracket-expression '\\' case above (which merges into an already-accumulating
            // ranges local via putAll), this escape is the construct's entire content.
            int escapeStartIndex = index;
            CodePointSet escapeRanges = parseComplexEscape(); // advances past the escape
            ComplexCharacterPatternConstruct escapeChar = new ComplexCharacterPatternConstruct(escapeStartIndex, index, escapeRanges);
            escapeChar.flags = flags;
            accumulator = addToAlternative(accumulator, parseQuantifiable(escapeChar), altStartIndex);
          }
        }
      } else {
        int startIndex = index;
        if (isQuantifierChar(peek)) {
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
        if (isQuantifierChar(peek)) {
          // Unlike the other two flush sites, rawTextStartIndex alone isn't enough here: this
          // very character may have just opened the run (rawTextStartIndex == rawTextPureEnd,
          // nothing pure actually accumulated yet -- it's about to become its own quantified
          // ComplexCharacterPatternConstruct instead, never joining a literal at all) -- rawText.length() alone
          // isn't enough either, symmetrically, since a pure run never touches rawText until it
          // stops being pure. Must check whichever of the two actually holds this run's content.
          boolean hasPendingLiteral = rawTextIsPure
              ? rawTextPureEnd > rawTextStartIndex
              : castNonNull(rawText).length() > 0;
          if (hasPendingLiteral) {
            CharSequence literalValue = rawTextIsPure
                ? CharBuffer.wrap(pattern, rawTextStartIndex, rawTextPureEnd)
                : castNonNull(rawText).toString();
            LiteralPatternConstruct literal = new LiteralPatternConstruct(rawTextStartIndex, index, literalValue);
            literal.flags = flags;
            accumulator = addToAlternative(accumulator, literal, altStartIndex);
            if (rawText != null) {
              rawText.setLength(0);
            }
          }
          ComplexCharacterPatternConstruct complex = singleCharacter(startIndex, fullChar);
          complex.flags = flags;
          complex.endIndex = index;
          accumulator = addToAlternative(accumulator, parseQuantifiable(complex), altStartIndex);
          rawTextStartIndex = -1;
          rawTextIsPure = true;
        } else if (rawTextIsPure) {
          rawTextPureEnd = afterFullChar;
        } else {
          appendCodePoint(castNonNull(rawText), fullChar);
        }
      }
    }
  }

  /**
   * Appends {@code pc} to the current alternative's lazily-built accumulator (see {@link
   * #parseUnion}'s own doc on {@code accumulator}), allocating the real {@link SequencePatternConstruct} (and its
   * backing {@code ArrayList}) only once a second element actually shows up. Alloc sampling
   * showed this constructor (unconditional, once per alternative, in the old design) at 5.6% of
   * compile-time allocation -- a real cost for the common case of a single-element alternative
   * (a lone atom before a "|", or the sole element of a non-capturing group/pattern with no "|"
   * at all), which never needed a wrapping {@code SequencePatternConstruct} in the first place.
   *
   * @param accumulator the alternative's contents so far: {@code null} (empty), a bare {@code
   *     PatternConstruct} (exactly one element, no {@code SequencePatternConstruct} needed yet), or a {@code
   *     SequencePatternConstruct} (2+ elements, already flattened into it)
   * @param altStartIndex the current alternative's start position, used as the {@code SequencePatternConstruct}'s
   *     {@code startIndex} if/when one actually needs allocating
   * @return the updated accumulator, in the same three-shape encoding
   */
  private static Object addToAlternative(
      @Nullable Object accumulator, PatternConstruct pc, int altStartIndex) {
    if (accumulator instanceof SequencePatternConstruct) {
      ((SequencePatternConstruct) accumulator).patterns.add(pc);
      return accumulator;
    }
    if (accumulator == null) {
      return pc;
    }
    SequencePatternConstruct sequence = new SequencePatternConstruct(altStartIndex);
    sequence.patterns.add((PatternConstruct) accumulator);
    sequence.patterns.add(pc);
    return sequence;
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

  private @Nullable PatternConstruct parseGroup() {
    if (peek != '(') {
      throw new IllegalStateException("entered parseGroup at illegal start point");
    }
    int groupStartIndex = index;
    int entryFlags = flags;
    advance(1);
    // Mirrors QuantifiedUnionPatternConstruct.captureConstructIndex's own default (0, meaning "wants a real index,
    // not yet assigned") until parseUnion() actually needs to be told which one -- kept as locals
    // here (rather than pre-allocating the union itself) so a non-capturing, unquantified,
    // single-alternative group -- by far the common case for "(?:...)" -- never allocates a
    // QuantifiedUnionPatternConstruct at all.
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
          while (isAsciiAlphanumeric(peek)) {
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
          if (namedGroups != null && namedGroups.containsKey(captureName)) {
            // Checked here, not where namedGroups is actually populated below -- java.util.regex
            // rejects the redefinition itself, before even looking at the group's own body, and
            // this is the point where the name (and its position, for the error) is in scope.
            throw PatternSyntaxException.throwWithReferences(
                pattern, startName, "Named capturing group <", captureName, "> is already defined");
          }
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
          // did, leaving QuantifiedUnionPatternConstruct's captureConstructIndex at its default of 0, i.e.
          // "capturing group 0". That corrupted the whole pattern's capture bookkeeping: the "(?s)"
          // construct got (wrongly) counted and compiled as a real capturing group despite never
          // going through the increment/parseUnion machinery below (the bare "(?...)" form returns
          // immediately, a few lines down) or, for "(?...:...)", getting wrongly double-processed
          // by that machinery as group 0 on top of whatever real group 0 already existed --
          // producing a captureGroups array sized for 0 real groups while still trying to write
          // into slot 0, an ArrayIndexOutOfBoundsException at match time.
          groupCaptureIndex = -1;
          int flagValue;
          int enableFlags = 0;
          int disableFlags = 0;
          while ((flagValue = InlineFlags.valueOf(peek)) != 0) {
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
            enableFlags |= flagValue;
            advance(1);
          }
          if (peek == '-') {
            advance(1);
            while ((flagValue = InlineFlags.valueOf(peek)) != 0) {
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
              disableFlags |= flagValue;
              advance(1);
            }
          }
          // UNICODE_CHARACTER_CLASS implies UNICODE_CASE, on and off, as in java.util.regex.
          if ((enableFlags & UnicodeFlags.UNICODE_CHARACTER_CLASS) != 0) {
            enableFlags |= UnicodeFlags.UNICODE_CASE;
          }
          if ((disableFlags & UnicodeFlags.UNICODE_CHARACTER_CLASS) != 0) {
            disableFlags |= UnicodeFlags.UNICODE_CASE;
          }
          if (peek == ')') {
            // A flags-only construct ("(?i)") isn't itself quantifiable and has no body to parse --
            // still needs a real (empty) QuantifiedUnionPatternConstruct, since that's the shape #parse()'s caller
            // (an ordinary sequence element) expects back.
            QuantifiedUnionPatternConstruct emptyUnion = new QuantifiedUnionPatternConstruct(pattern, groupStartIndex);
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
        if (namedGroups == null) {
          namedGroups = new MutableObjectIntMap<>(4);
        }
        namedGroups.set(captureName, groupCaptureIndex);
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
      // parseUnion() guarantees a real QuantifiedUnionPatternConstruct whenever captureConstructIndex != -1 -- see
      // its own doc.
      if (closedGroupsByIndex == null) {
        closedGroupsByIndex = new MutableIntObjectMap<>(4);
      }
      closedGroupsByIndex.set(groupCaptureIndex, (QuantifiedUnionPatternConstruct) body);
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
   * already a {@code QuantifiedUnionPatternConstruct} (a capturing group, or a non-capturing group with more than
   * one alternative -- either way, {@link #parseUnion} already had to allocate one), the quantifier
   * is applied to it directly, exactly as it always was. Otherwise {@code body} is a bare {@code
   * SequencePatternConstruct} (a non-capturing, single-alternative group, whose wrapping union {@link #parseUnion}
   * deferred) -- wrapped in a fresh one-branch {@code QuantifiedUnionPatternConstruct} only if a quantifier actually
   * follows, the same wrap-and-check idiom {@link #quantifySingleConstruct} uses for a
   * backreference or {@code \X}.
   */
  private PatternConstruct quantifyGroupBody(
      PatternConstruct body, int groupStartIndex, int groupFlags) {
    if (body instanceof QuantifiedUnionPatternConstruct) {
      parseQuantifiable((QuantifiedUnionPatternConstruct) body);
      return body;
    }
    skipComments();
    if (!isQuantifierChar(peek)) {
      return body;
    }
    QuantifiedUnionPatternConstruct wrapper = new QuantifiedUnionPatternConstruct(pattern, groupStartIndex);
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
    LookbehindPatternConstruct.SingleCodePointBody resolved = body.resolveSingleCodePointBody();
    if (resolved == null) {
      throw throwUnexpectedChar(
          "lookbehind is only supported when its body always matches exactly one code point (a "
              + "single character or character class, or an alternation of such, optionally "
              + "wrapped in one capturing group around the whole body) -- did you mean a "
              + "single-character class like [ab] instead of \"",
          pattern.substring(startIndex, index),
          "\"?");
    }
    LookbehindPatternConstruct lookbehind = new LookbehindPatternConstruct(
        pattern, startIndex, index, isPositive, resolved.codePoints, resolved.captureConstructIndex);
    lookbehind.flags = flags;
    // Not itself quantifiable in this engine, same as \b/\B/^/$ -- see keepZeroWidthAfterQuantifier's
    // own doc. A "{0}" bound elides it entirely (returns null), same as those other constructs.
    return keepZeroWidthAfterQuantifier() ? lookbehind : null;
  }

  /** Any {@code \\b{...}}/{@code \\B{...}} boundary-type suffix other than the plain {@code \\b{g}}
   *  already handled by this method's caller (grapheme boundary -- {@code \\B{g}} is NOT special
   *  syntax, matching java.util.regex) isn't supported; without this the "{...}" (a "{" not
   *  starting a quantifier) would silently be read as literal text after a plain word boundary. */
  private void rejectBoundaryType() {
    if (peek == '{' && !(index + 1 < pattern.length() && startsBracedQuantifier(pattern.charAt(index + 1)))) {
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
          GraphemeBoundaryPatternConstruct g = new GraphemeBoundaryPatternConstruct(startIndex, index);
          g.flags = flags;
          return g;
        }
        rejectBoundaryType();
        WordBoundaryPatternConstruct b = new WordBoundaryPatternConstruct(pattern, index-2, index, /* isWordBoundary= */ true);
        b.flags = flags;
        return b;
      }
      case 'B': {
        advance(2);
        rejectBoundaryType();
        WordBoundaryPatternConstruct b = new WordBoundaryPatternConstruct(pattern, index-2, index, /* isWordBoundary= */ false);
        b.flags = flags;
        return b;
      }
      case 'A': {
        advance(2);
        BoundaryPatternConstruct b = new BoundaryPatternConstruct(index-2, index, BoundaryEnum.InputBegin);
        b.flags = flags;
        return b;
      }
      case 'Z': {
        advance(2);
        BoundaryPatternConstruct b = new BoundaryPatternConstruct(index-2, index, BoundaryEnum.InputEndExceptTerminator);
        b.flags = flags;
        return b;
      }
      case 'z': {
        advance(2);
        BoundaryPatternConstruct b = new BoundaryPatternConstruct(index-2, index, BoundaryEnum.InputEnd);
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
   * Wraps a construct that isn't itself a {@code QuantifiablePatternConstruct} (a backreference, or
   * {@code \X}) in a one-branch, non-capturing union so a following quantifier (e.g. {@code \X+})
   * has somewhere to attach; returns the construct as-is if nothing follows.
   */
  private PatternConstruct quantifySingleConstruct(PatternConstruct construct) {
    skipComments();
    if (!isQuantifierChar(peek)) {
      return construct;
    }
    QuantifiedUnionPatternConstruct wrapper = new QuantifiedUnionPatternConstruct(pattern, construct.startIndex);
    wrapper.captureConstructIndex = -1;
    SequencePatternConstruct body = new SequencePatternConstruct(construct.startIndex);
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
   * already-parsed {@code QuantifiedUnionPatternConstruct} immediately (via {@code closedGroupsByIndex}/{@code
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
      QuantifiedUnionPatternConstruct referenced = closedGroupsByIndex != null ? closedGroupsByIndex.get(referencedIndex) : null;
      if (referenced == null) {
        throw PatternSyntaxException.throwWithReferences(
            pattern,
            startIndex,
            "backreference \\", groupNumber, " refers to a group that either doesn't exist or ",
            "hasn't been closed yet at this point in the pattern (forward references aren't ",
            "supported) -- ", captureConstructIndex, " capturing group(s) defined so far");
      }
      advance(digitsEnd - index);
      BackReferencePatternConstruct backReference = new BackReferencePatternConstruct(startIndex, index, referencedIndex, referenced);
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
      while (isAsciiAlphanumeric(peek)) {
        advance(1);
      }
      if (peek != '>') {
        throw throwUnexpectedChar(
            "Character not allowed in backreference name. Expected '>' to match ",
            new CodePointReference(startName));
      }
      String name = pattern.substring(startName, index);
      advance(1);
      // -1 sentinel: a real captureConstructIndex is always >= 0, so this distinguishes "absent"
      // from index 0's real group without needing a boxed Integer/null (see namedGroups' own doc).
      int referencedIndex = namedGroups != null ? namedGroups.getOrDefault(name, -1) : -1;
      if (referencedIndex == -1) {
        throw PatternSyntaxException.throwWithReferences(
            pattern,
            startIndex,
            "backreference \\k<", name, "> refers to a named group that either doesn't exist or ",
            "hasn't been closed yet at this point in the pattern (forward references aren't ",
            "supported)");
      }
      QuantifiedUnionPatternConstruct referenced = closedGroupsByIndex != null ? closedGroupsByIndex.get(referencedIndex) : null;
      if (referenced == null) {
        throw PatternSyntaxException.throwWithReferences(
            pattern,
            startIndex,
            "backreference \\k<", name, "> refers to a named group that hasn't been closed yet at ",
            "this point in the pattern (forward references aren't supported)");
      }
      BackReferencePatternConstruct backReference = new BackReferencePatternConstruct(startIndex, index, referencedIndex, referenced);
      backReference.flags = flags;
      return backReference;
    }
    return null;
  }

  private PatternConstruct parseQuantifiable(ComplexCharacterPatternConstruct construct) {
    // construct.flags is already set by whoever built it (every ComplexCharacterPatternConstruct creation site
    // sets it directly, since it also needs the correct value for the never-quantified case, which
    // never reaches here at all).
    //
    // skipComments() first, then peek for an actual quantifier suffix, so the overwhelmingly
    // common unquantified case (a lone bracket class/"."/ escape with nothing after it) can return
    // `construct` itself unwrapped instead of always allocating a ComplexQuantifiedCharacterPatternConstruct just
    // to immediately discover there's nothing to quantify -- this was PatternParser's #3
    // allocation site by CPU-sampling weight (~6% of Pattern.compile's allocations; see notes.md).
    // Safe to skip the generic parseQuantifiable(T) overload entirely here: when none of
    // '?'/'*'/'+'/'{' follow, that overload's own body is a no-op (its trailing reluctant/
    // possessive check can only see a '?'/'+' here if one of those branches already consumed a
    // real quantifier first).
    skipComments();
    if (!isQuantifierChar(peek)) {
      return construct;
    }
    return parseQuantifiable(new ComplexQuantifiedCharacterPatternConstruct(pattern, index, construct));
  }

  /**
   * Consumes a quantifier suffix after a zero-width construct ({@code ^ $  \B \A \Z \z}), as
   * java.util.regex allows. A zero-width assertion is idempotent, so {@code X{min,max}} is just
   * {@code X} when {@code min >= 1} and matches nothing extra when {@code min == 0}. Returns whether
   * the construct itself must still be added to the sequence.
   */
  private boolean keepZeroWidthAfterQuantifier() {
    skipComments();
    if (!isQuantifierChar(peek)) {
      return true;
    }
    int savedQuantifiableIndex = quantifiableIndex;
    QuantifiablePatternConstruct quantifier = parseQuantifiable(new ScratchQuantifierHolderPatternConstruct(pattern, index));
    quantifiableIndex = savedQuantifiableIndex; // the scratch holder needs no loop counter slot
    return quantifier.min >= 1;
  }

  private <T extends QuantifiablePatternConstruct> T parseQuantifiable(T construct) {
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
      // the construct anyway -- see QuantifiablePatternConstruct#possessive -- since the two are no longer
      // interchangeable for the loop/zero-width-assertion ambiguity check buildLoopMatcher runs:
      // possessive genuinely never backtracks in java.util.regex either, so it's exempt from a
      // rejection that greedy syntax needs (see that field's own doc). Reluctant ('?') is recorded
      // on the construct too -- see QuantifiablePatternConstruct#reluctant.
      construct.reluctant = peek == '?';
      construct.possessive = peek == '+';
      advance(1);
      construct.endIndex = index;
    }
    return construct;
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
