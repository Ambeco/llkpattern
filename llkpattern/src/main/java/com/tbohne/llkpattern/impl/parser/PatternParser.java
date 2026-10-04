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
  // Group -> "?" ">" UnionConstruct   // atomic group: parsed as "?" ":" (no backtracking, so already atomic)
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
  // Text -> "\" "Q" ([^\][^E])* "\" "E" Text? // rewritten to escaped literals before parsing (PatternText.removeQuoting)
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
  // \G has no construct: it only tells find() to anchor at the previous match end.
  private boolean anchorsToPreviousMatchEnd = false;
  // Shared and never mutated: returned by getNamedGroups() when there are no named groups.
  private static final ObjectIntMap<String> EMPTY_NAMED_GROUPS = new MutableObjectIntMap<>(0);

  // androidx maps (no boxing, flat tables), and null until first use: most patterns have no named
  // or capturing groups, and these allocations showed up in compile-time allocation sampling.
  private @Nullable MutableObjectIntMap<String> namedGroups;
  // Filled when a group's ")" is reached, so backreferences can only see already-closed groups
  // (forward and self references are rejected).
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

  public boolean anchorsToPreviousMatchEnd() {
    return anchorsToPreviousMatchEnd;
  }

  public PatternConstruct parse() {
    if ((flags & Pattern.LITERAL) != 0) {
      return parseLiteralPattern();
    }
    // Not a capturing group, so capture index -1. A single-alternative root comes back as a bare
    // SequencePatternConstruct (no union).
    PatternConstruct root = parseUnion(0, flags, /* captureConstructIndex= */ -1, /* captureName= */ "");
    if (index < pattern.length()) {
      // This can trigger if the user has one too many ')'
      throw throwUnexpectedChar("Too many \")\". Check that the () parenthesis match");
    }
    return root;
  }

  // LITERAL: only CASE_INSENSITIVE/UNICODE_CASE still apply, as in java.util.regex.
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

  // The literal run being accumulated by parseUnion. A nested group is only parsed while the run is
  // empty (it is flushed at "("), so the state is shared across recursion.
  private int runStartIndex = -1;
  // While true, the run is a verbatim copy of `pattern` over runStartIndex..runPureEnd (no decoded
  // escape, no COMMENTS-mode gap), so it is read via a zero-copy CharBuffer.wrap instead of being
  // copied into `runText`. Once an escape or a gap breaks purity, the pure prefix is copied into
  // `runText` once and accumulation continues there.
  private boolean runIsPure = true;
  private int runPureEnd = -1; // meaningful only while runIsPure && runStartIndex >= 0
  // Lazy (StringBuilder.<init> was ~4.5% of compile CPU); reused across impure runs via setLength(0).
  private @Nullable StringBuilder runText;

  /**
   * Parses {@code '|'}-separated alternatives up to (not consuming) the matching {@code ')'} or
   * end of pattern.
   *
   * <p>A {@code QuantifiedUnionPatternConstruct} is allocated only when required: more than one
   * alternative, or {@code captureConstructIndex != -1} (a capturing group must carry its index).
   * Otherwise the bare {@code SequencePatternConstruct} is returned and the caller wraps it if a
   * quantifier follows. {@code captureConstructIndex}/{@code captureName} are -1/"" for a
   * non-capturing caller.
   */
  private PatternConstruct parseUnion(
      int unionStartIndex, int unionFlags, int captureConstructIndex, String captureName) {
    // Current alternative, built lazily to skip a SequencePatternConstruct+ArrayList for
    // single-element alternatives: null (empty), a bare PatternConstruct (one element), or a
    // SequencePatternConstruct (2+). See addToAlternative.
    Object accumulator = null;
    int altStartIndex = index;
    // Null until a second alternative is seen, so the union is allocated once.
    PatternConstruct firstAlternative = null;
    QuantifiedUnionPatternConstruct union = null;
    for (; ; ) {
      skipComments();
      // ']' is deliberately absent: outside a bracket expression it is an ordinary literal, as in
      // java.util.regex.
      if (peek == '(' || peek == ')' || peek == '[' || peek == '|' || peek == '.'
          || peek == '^' || peek == '$' || peek == EOF) {
        accumulator = flushLiteralRun(accumulator, altStartIndex, index);
        switch (peek) {
          case '(':
            // Null only for a lookbehind quantified to "{0}" (see keepZeroWidthAfterQuantifier).
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
            PatternConstruct altConstruct = finishAlternative(accumulator, altStartIndex, unionStartIndex);
            if (union != null) {
              union.constructs.add(altConstruct);
            } else if (firstAlternative == null) {
              firstAlternative = altConstruct;
            } else {
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
            // Must advance before parseQuantifiable, or ".*" is read as "." then literal "*".
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
            return finishUnion(accumulator, altStartIndex, unionStartIndex, unionFlags,
                captureConstructIndex, captureName, firstAlternative, union);
        }
      } else if (peek == '\\') {
        int startIndex = index;
        int codePoint = tryParseSingleCharEscape();
        if (codePoint != -1) {
          accumulator = parseEscapedCharacter(accumulator, altStartIndex, startIndex, codePoint);
        } else {
          accumulator = flushLiteralRun(accumulator, altStartIndex, index);
          accumulator = parseNonLiteralEscape(accumulator, altStartIndex);
        }
      } else {
        accumulator = parsePlainCharacter(accumulator, altStartIndex);
      }
    }
  }

  /**
   * Emits the pending literal run (if any) ending at {@code endIndex} into the alternative and
   * resets the run. Returns the new accumulator.
   */
  private @Nullable Object flushLiteralRun(
      @Nullable Object accumulator, int altStartIndex, int endIndex) {
    if (runStartIndex < 0) {
      return accumulator;
    }
    // This char may have just opened the run with nothing accumulated yet, so check whichever of
    // the pure span or runText actually holds the run.
    boolean hasPendingLiteral = runIsPure ? runPureEnd > runStartIndex : castNonNull(runText).length() > 0;
    if (hasPendingLiteral) {
      accumulator = addToAlternative(
          accumulator,
          newLiteral(runStartIndex, endIndex, runIsPure, runPureEnd, runText),
          altStartIndex);
    }
    if (runText != null) {
      runText.setLength(0);
    }
    runStartIndex = -1;
    runIsPure = true;
    return accumulator;
  }

  /** Handles a single-character escape ({@code \n}, {@code \x41}, ...): literal, or an atom if quantified. */
  private @Nullable Object parseEscapedCharacter(
      @Nullable Object accumulator, int altStartIndex, int startIndex, int codePoint) {
    skipComments();
    if (isQuantifierChar(peek)) {
      // The quantifier belongs to this escaped character alone, not the literal run before it.
      accumulator = flushLiteralRun(accumulator, altStartIndex, startIndex);
      ComplexCharacterPatternConstruct complex = singleCharacter(startIndex, codePoint);
      complex.flags = flags;
      complex.endIndex = index;
      return addToAlternative(accumulator, parseQuantifiable(complex), altStartIndex);
    }
    if (runStartIndex < 0) {
      runStartIndex = startIndex;
    } else if (runIsPure) {
      // A decoded escape never equals its source text, so the run stops being pure here.
      runText = ensureRawText(runText, runPureEnd - runStartIndex, startIndex);
      runText.append(pattern, runStartIndex, runPureEnd);
    }
    runIsPure = false;
    runText = ensureRawText(runText, 0, startIndex);
    appendCodePoint(runText, codePoint);
    return accumulator;
  }

  /** Handles an ordinary literal character: extends the run, or becomes an atom if quantified. */
  private @Nullable Object parsePlainCharacter(@Nullable Object accumulator, int altStartIndex) {
    int startIndex = index;
    if (isQuantifierChar(peek)) {
      // Quantifiers after an atom are consumed by parseQuantifiable, so one seen here has
      // nothing to repeat ("*a", "a|+b", "a**"), which java.util.regex also rejects.
      throw throwUnexpectedChar(
          " quantifier with nothing to repeat. Did you mean to escape it with a backslash, or to put "
              + "it after the character, group or class it should repeat?");
    }
    if (runStartIndex < 0) {
      runStartIndex = startIndex;
      runPureEnd = startIndex;
    } else if (runIsPure && startIndex != runPureEnd) {
      // A COMMENTS-mode gap was skipped: it must not join the literal, so the run leaves pure mode.
      runText = ensureRawText(runText, runPureEnd - runStartIndex, startIndex);
      runText.append(pattern, runStartIndex, runPureEnd);
      runIsPure = false;
    }
    int fullChar = Character.codePointAt(patternChars, index);
    advanceCodePoint();
    // Captured before skipComments() below moves `index` past a following gap; runPureEnd
    // must stay here or the next char's gap check above would never fire.
    int afterFullChar = index;
    // Under COMMENTS a quantifier may follow whitespace/a comment ("a * b" is "a*b").
    skipComments();
    if (isQuantifierChar(peek)) {
      accumulator = flushLiteralRun(accumulator, altStartIndex, index);
      ComplexCharacterPatternConstruct complex = singleCharacter(startIndex, fullChar);
      complex.flags = flags;
      complex.endIndex = index;
      return addToAlternative(accumulator, parseQuantifiable(complex), altStartIndex);
    }
    if (runIsPure) {
      runPureEnd = afterFullChar;
    } else {
      appendCodePoint(castNonNull(runText), fullChar);
    }
    return accumulator;
  }

  /** Ends the current alternative at the {@code |} (or end), returning it as a bare construct or sequence. */
  private PatternConstruct finishAlternative(
      @Nullable Object accumulator, int altStartIndex, int unionStartIndex) {
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
    return altConstruct;
  }

  /** Builds parseUnion's result once its final alternative ({@code accumulator}) is complete. */
  private PatternConstruct finishUnion(
      @Nullable Object accumulator,
      int altStartIndex,
      int unionStartIndex,
      int unionFlags,
      int captureConstructIndex,
      String captureName,
      @Nullable PatternConstruct firstAlternative,
      @Nullable QuantifiedUnionPatternConstruct union) {
    if (accumulator == null) {
      throw throwEmptySequence(altStartIndex, unionStartIndex);
    }
    // The final alternative is always a real SequencePatternConstruct, even with one
    // element: other code relies on that shape (CLAUDE.md "Parser root shape").
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
      QuantifiedUnionPatternConstruct singleBranch = new QuantifiedUnionPatternConstruct(pattern, unionStartIndex);
      singleBranch.captureConstructIndex = captureConstructIndex;
      singleBranch.captureName = captureName;
      singleBranch.constructs.add(finalSequence);
      singleBranch.endIndex = index;
      return singleBranch;
    }
    return finalSequence;
  }

  /** Parses a backslash construct that is not a single literal character; returns the new accumulator. */
  private @Nullable Object parseNonLiteralEscape(@Nullable Object accumulator, int altStartIndex) {
    if (peek == '\\' && index + 1 < pattern.length() && pattern.charAt(index + 1) == 'G') {
      // Rejected anywhere but the very start: java.util.regex silently ignores it there, but
      // this engine rejects unsatisfiable constructs.
      if (index != 0) {
        throw throwUnexpectedChar(
            "\\G is only allowed as the very first thing in the pattern -- it doesn't "
                + "match a position in the input, it just anchors find() to exactly where "
                + "the previous match ended (rather than scanning forward for one)");
      }
      advance(2);
      anchorsToPreviousMatchEnd = true;
    return accumulator;
    }
    PatternConstruct backReference = tryParseBackReference();
    if (backReference != null) {
      accumulator = addToAlternative(accumulator, quantifyBackReference(backReference), altStartIndex);
    return accumulator;
    }
    if (peek == '\\' && index + 1 < pattern.length() && pattern.charAt(index + 1) == 'X') {
      int graphemeStartIndex = index;
      advance(2);
      GraphemeClusterPatternConstruct graphemeCluster =
          new GraphemeClusterPatternConstruct(graphemeStartIndex, index);
      graphemeCluster.flags = flags;
      accumulator = addToAlternative(accumulator, quantifySingleConstruct(graphemeCluster), altStartIndex);
    return accumulator;
    }
    PatternConstruct boundaryConstruct = tryParseBoundary();
    if (boundaryConstruct != null) {
      if (keepZeroWidthAfterQuantifier()) {
        accumulator = addToAlternative(accumulator, boundaryConstruct, altStartIndex);
      }
    } else {
      int escapeStartIndex = index;
      CodePointSet escapeRanges = parseComplexEscape(); // advances past the escape
      ComplexCharacterPatternConstruct escapeChar = new ComplexCharacterPatternConstruct(escapeStartIndex, index, escapeRanges);
      escapeChar.flags = flags;
      accumulator = addToAlternative(accumulator, parseQuantifiable(escapeChar), altStartIndex);
    }
    return accumulator;
  }

  private LiteralPatternConstruct newLiteral(
      int runStartIndex, int endIndex, boolean isPure, int pureEnd, @Nullable StringBuilder rawText) {
    CharSequence value = isPure
        ? CharBuffer.wrap(pattern, runStartIndex, pureEnd)
        : castNonNull(rawText).toString();
    LiteralPatternConstruct literal = new LiteralPatternConstruct(runStartIndex, endIndex, value);
    literal.flags = flags;
    return literal;
  }

  /**
   * Appends {@code pc} to the lazily-built accumulator (see {@link #parseUnion}), allocating the
   * real {@code SequencePatternConstruct} only once a second element shows up (it was 5.6% of
   * compile-time allocation). Accumulator shapes: null, a bare construct, or a sequence.
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

  // Approximate (only sizes rawText); over-estimates because escapes decode shorter.
  private static final String LITERAL_RUN_DELIMITERS = "(){}[]|.^$?+*";

  // Upper bound on how much more rawText needs for a run resuming at fromIndex.
  private int literalRunCapacityHint(int fromIndex) {
    int len = pattern.length();
    int i = fromIndex;
    while (i < len && LITERAL_RUN_DELIMITERS.indexOf(pattern.charAt(i)) < 0) {
      i++;
    }
    return i - fromIndex;
  }

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
    // 0 means "wants a real index, not yet assigned"; kept as locals so a plain "(?:...)" never
    // allocates a union.
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
          captureName = parseCaptureName();
          break;
        case ':':
        case '>':
          // "(?>X)" is just "(?:X)": with no backtracking every group already matches atomically.
          groupCaptureIndex = -1;
          advance(1);
          break;
        case '=':
        case '!':
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
          // Flag constructs are non-capturing; leaving the default 0 here once corrupted capture bookkeeping.
          groupCaptureIndex = -1;
          QuantifiedUnionPatternConstruct flagsOnly = parseInlineFlags(groupStartIndex);
          if (flagsOnly != null) {
            return flagsOnly;
          }
          restoreFlagsOnExit = true;
          break;
        default:
          throw throwUnexpectedChar("Not a valid group special construct for a capture group.");
      }
    }
    // The index must be assigned here, in opening-paren order, before recursing: assigning after
    // parseUnion numbered an outer group higher than its nested ones.
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
   * Parses the flag letters of {@code (?i)} or {@code (?i:...)}, applying them to {@code flags}.
   * Returns the empty union for the bare {@code (?i)} form, or null once {@code (?i:} is consumed.
   */
  private @Nullable QuantifiedUnionPatternConstruct parseInlineFlags(int groupStartIndex) {
    int flagValue;
    int enableFlags = 0;
    int disableFlags = 0;
    while ((flagValue = InlineFlags.valueOf(peek)) != 0) {
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
      // "(?i)" is not quantifiable and has no body, but callers expect an (empty) union.
      QuantifiedUnionPatternConstruct emptyUnion = new QuantifiedUnionPatternConstruct(pattern, groupStartIndex);
      emptyUnion.captureConstructIndex = -1;
      emptyUnion.endIndex = index;
      advance(1);
      flags = (flags | enableFlags) & ~disableFlags;
      return emptyUnion;
    } else if (peek == ':') {
      advance(1);
      flags = (flags | enableFlags) & ~disableFlags;
      return null;
    } else {
      throw throwUnexpectedChar("That character is illegal in group special construct.");
    }
  }

  // Consumes "name>" after "(?<" and returns the name.
  private String parseCaptureName() {
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
    String captureName = pattern.substring(startName, index);
    if (namedGroups != null && namedGroups.containsKey(captureName)) {
      throw PatternSyntaxException.throwWithReferences(
          pattern, startName, "Named capturing group <", captureName, "> is already defined");
    }
    advance(1);
    return captureName;
  }

  // A non-capturing single-alternative body is a bare sequence; it is wrapped in a union only if a quantifier follows.
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
   * {@code (?<=X)}/{@code (?<!X)}, entered with the {@code '='}/{@code '!'} peeked, not consumed.
   *
   * <p>Only a body that always matches exactly one code point is supported (see design.md's
   * "Boundary matching"). The body is parsed like a non-capturing group, so a nested capturing
   * group is numbered normally; the one-code-point check runs afterward.
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
    return keepZeroWidthAfterQuantifier() ? lookbehind : null;
  }

  // Without this, "\b{...}" (other than \b{g}) would read the "{" as literal text.
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

  private PatternConstruct quantifyBackReference(PatternConstruct backReference) {
    return quantifySingleConstruct(backReference);
  }

  // Wraps a non-quantifiable construct (backreference, \X) in a one-branch union only if a quantifier follows.
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
   * {@code \1}-{@code \9} or {@code \k<name>}, or null if neither starts here. Forward references
   * and undefined groups are rejected at parse time (design.md "Backreferences").
   *
   * <p>As in {@code java.util.regex}, further digits are consumed only while the number doesn't
   * exceed the groups opened so far: {@code \12} is group 12 once 12 are open, else group 1 then "2".
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
      // -1 sentinel avoids boxing; real indices are >= 0.
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
    // Return the construct unwrapped when nothing quantifies it, to avoid allocating a
    // ComplexQuantifiedCharacterPatternConstruct per atom (~6% of compile allocation). The
    // construct's flags are already set by whoever built it.
    skipComments();
    if (!isQuantifierChar(peek)) {
      return construct;
    }
    return parseQuantifiable(new ComplexQuantifiedCharacterPatternConstruct(pattern, index, construct));
  }

  /**
   * Consumes a quantifier after a zero-width construct ({@code ^ $ \B \A \Z \z}, lookbehind), as
   * java.util.regex allows. Assertions are idempotent, so {@code X{min,max}} is {@code X} when
   * {@code min >= 1} and nothing when {@code min == 0}. Returns whether to keep the construct.
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
    skipComments();
    // Records the flags in effect here so CASE_INSENSITIVE etc. stay scoped to an inline "(?i:...)".
    construct.flags = flags;
    if (peek == '?') {
      construct.min = 0;
      // Even max == 1 needs a counter slot, to reject a second attempt.
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
      parseExplicitQuantifier(construct);
    }
    parseQuantifierModifier(construct);
    return construct;
  }

  /** Parses {@code {n}}, {@code {n,}} or {@code {n,m}}, with {@code peek} on the opening brace. */
  private void parseExplicitQuantifier(QuantifiablePatternConstruct construct) {
    advance(1);
    int startQuantifierIndex = index;
    construct.min = parseQuantifierBound(
        "first parameter of explicit quantifier '{' must be a number written with ASCII characters",
        "first parameter of explicit quantifier '{' must be less than ");
    construct.max = construct.min;
    construct.quantifiableIndex = quantifiableIndex++;
    if (peek == ',') {
      advance(1);
      if (isAsciiDigit(peek)) {
        construct.max = parseQuantifierBound(
            "second parameter of explicit quantifier '{' must be a number",
            "second parameter of explicit quantifier '{' must be less than ");
      } else {
        construct.max = Integer.MAX_VALUE;
      }
      if (peek != '}') {
        throw throwUnexpectedChar(
            "Expected '}' to end quantifier started at ",
            new CodePointReference(startQuantifierIndex));
      }
    } else if (peek != '}') {
      throw throwUnexpectedChar(
          "Expected ',' or '}' to end quantifier started at ",
          new CodePointReference(startQuantifierIndex));
    }
    advance(1);
    construct.endIndex = index;
  }

  private int parseQuantifierBound(String missingDigitsMessage, String overflowMessage) {
    int end = index;
    while (end < pattern.length() && isAsciiDigit(pattern.charAt(end))) {
      ++end;
    }
    if (end == index) {
      throw throwUnexpectedChar(missingDigitsMessage);
    }
    int value;
    try {
      value = Integer.parseInt(pattern, index, end, 10);
    } catch (NumberFormatException e) {
      throw throwUnexpectedChar(overflowMessage, Integer.MAX_VALUE);
    }
    advance(end - index);
    return value;
  }

  private static boolean isAsciiDigit(int c) {
    return c >= '0' && c <= '9';
  }

  private void parseQuantifierModifier(QuantifiablePatternConstruct construct) {
    if (peek == '?' || peek == '+') {
      // Possessive compiles like greedy (no backtracking) but is recorded: it is exempt from the
      // loop/zero-width ambiguity rejection that greedy needs (see
      // QuantifiablePatternConstruct#possessive). Reluctant is recorded too.
      construct.reluctant = peek == '?';
      construct.possessive = peek == '+';
      advance(1);
      construct.endIndex = index;
    }
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
