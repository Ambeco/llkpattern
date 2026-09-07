package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * CASE_INSENSITIVE/UNICODE_CASE and inline flag toggle coverage. All were entirely broken until
 * 2026-09-06 -- see remaining_work.md's "CASE_INSENSITIVE/UNICODE_CASE, inline flag toggles, and
 * DOTALL" entry for the history, and the two follow-up entries it left behind (a bare "(?s)"
 * breaking the surrounding sequence, and inline flag toggles not actually locally scoping
 * anything) -- both now also fixed, see the tests below.
 */
@RunWith(JUnit4.class)
public class CaseInsensitiveTest {
  @Test
  public void literal_caseInsensitive_matchesEitherCase() {
    Ll1Pattern p = Ll1Pattern.compile("abc", Ll1Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("ABC").matches(), is(true));
    assertThat(p.matcher("aBc").matches(), is(true));
    assertThat(p.matcher("abc").matches(), is(true));
  }

  @Test
  public void literal_default_isCaseSensitive() {
    Ll1Pattern p = Ll1Pattern.compile("abc");
    assertThat(p.matcher("ABC").matches(), is(false));
  }

  @Test
  public void charClass_lowercaseRange_caseInsensitive_matchesUppercaseInput() {
    Ll1Pattern p = Ll1Pattern.compile("[a-z]+", Ll1Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("ABC").matches(), is(true));
  }

  @Test
  public void charClass_uppercaseRange_caseInsensitive_matchesLowercaseInput() {
    Ll1Pattern p = Ll1Pattern.compile("[A-Z]+", Ll1Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("abc").matches(), is(true));
  }

  @Test
  public void charClass_default_isCaseSensitive() {
    Ll1Pattern p = Ll1Pattern.compile("[a-z]+");
    assertThat(p.matcher("ABC").matches(), is(false));
  }

  @Test
  public void asciiOnly_doesNotFoldNonAsciiLetters() {
    // Kelvin sign U+212A uppercases to itself under simple ASCII folding but is the uppercase form
    // of 'k' under full Unicode case folding -- exactly the case CASE_INSENSITIVE alone (without
    // UNICODE_CASE) is documented to NOT handle.
    Ll1Pattern p = Ll1Pattern.compile("k", Ll1Pattern.CASE_INSENSITIVE);
    assertThat(p.matcher("K").matches(), is(false));
  }

  @Test
  public void unicodeCase_foldsBeyondAscii() {
    Ll1Pattern p =
        Ll1Pattern.compile("k", Ll1Pattern.CASE_INSENSITIVE | Ll1Pattern.UNICODE_CASE);
    assertThat(p.matcher("K").matches(), is(true));
  }

  @Test
  public void find_isCaseInsensitiveAcrossTheWholeMatch() {
    Ll1Pattern p = Ll1Pattern.compile("hello", Ll1Pattern.CASE_INSENSITIVE);
    Matcher m = p.matcher("say HELLO there");
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("HELLO"));
  }

  // --- inline flag toggles: used to throw on ANY flag letter, not just case ones ---

  @Test
  public void inlineFlag_wholePatternForm_compilesInsteadOfThrowing() {
    Ll1Pattern.compile("(?i)abc"); // used to throw PatternSyntaxException unconditionally
  }

  @Test
  public void inlineFlag_nonCaseFlag_alsoCompiles() {
    Ll1Pattern.compile("(?m)^abc$"); // same bug affected every flag letter, not just 'i'
  }

  @Test
  public void inlineFlag_groupForm_compiles() {
    Ll1Pattern.compile("(?i:abc)");
  }

  @Test
  public void inlineFlag_disableForm_compiles() {
    Ll1Pattern.compile("(?i-m:abc)");
  }

  // --- DOTALL: found broken (always-on, regardless of the flag) while fixing the above ---

  @Test
  public void dot_default_doesNotMatchNewline() {
    assertThat(Ll1Pattern.compile("a.b").matcher("a\nb").find(), is(false));
  }

  @Test
  public void dot_default_matchesOtherCharacters() {
    assertThat(Ll1Pattern.compile("a.b").matcher("axb").find(), is(true));
  }

  @Test
  public void dot_dotall_matchesNewline() {
    assertThat(Ll1Pattern.compile("a.b", Ll1Pattern.DOTALL).matcher("a\nb").find(), is(true));
  }

  // --- a flags-only group ((?s)/(?x)/etc, no ":") must not be miscounted as capturing group 0 ---

  @Test
  public void bareFlagsGroup_doesNotCorruptCaptureGroupNumbering() {
    // Used to throw ArrayIndexOutOfBoundsException at MATCH time: the bare "(?s)" form defaulted
    // to captureConstructIndex 0 (meaning "real capturing group 0") instead of -1 (non-capturing),
    // corrupting capture-group bookkeeping for the rest of the pattern -- a real group elsewhere
    // got miscounted, sizing Matcher#captureGroups too small. See also
    // bareFlagsGroup_isZeroWidthNoOp() below, for the separate (now also fixed) bug this one led to.
    Ll1Pattern p = Ll1Pattern.compile("(?s)(a)(b)");
    assertThat(p.matcher("").groupCount(), is(2));
  }

  @Test
  public void inlineFlag_repeatedLetter_stillRejected() {
    // The fix must not turn "already set" detection off entirely -- a genuine repeat like "(?ii)"
    // should still be a PatternSyntaxException, just not on the FIRST letter.
    org.junit.Assert.assertThrows(
        PatternSyntaxException.class, () -> Ll1Pattern.compile("(?ii)abc"));
  }

  // --- FIXED (2026-09-06): a bare flags-only group ("(?s)", no ":", no body) used to compile to
  // an empty, un-parsed QuantifiedUnion that broke the surrounding sequence -- find() on a pattern
  // with nothing but literal text after it incorrectly returned false. See
  // remaining_work.md/README.md; regression test added while fixing (found via corpus testing).

  @Test
  public void bareFlagsGroup_isZeroWidthNoOp() {
    assertThat(Ll1Pattern.compile("(?s)abx").matcher("abx").matches(), is(true));
  }

  @Test
  public void bareFlagsGroup_midPattern_isZeroWidthNoOp() {
    assertThat(Ll1Pattern.compile("a(?s)bx").matcher("abx").matches(), is(true));
  }

  @Test
  public void bareFlagsGroup_actuallyTogglesTheFlagForWhatFollows() {
    assertThat(Ll1Pattern.compile("(?s)a.b").matcher("a\nb").find(), is(true));
  }

  @Test
  public void bareFlagsGroup_asLastConstructInPattern_isZeroWidthNoOp() {
    // Here `next` is the pattern's own EndConstruct, not another Sequence element -- a different
    // path through the buildEntryMap passthrough than the other tests above.
    assertThat(Ll1Pattern.compile("abx(?s)").matcher("abx").matches(), is(true));
  }

  @Test
  public void bareFlagsGroup_asWholePattern_matchesEmptyString() {
    assertThat(Ll1Pattern.compile("(?s)").matcher("").matches(), is(true));
  }

  @Test
  public void bareFlagsGroup_consecutive_isStillZeroWidthNoOp() {
    // The second "(?s)"'s `next` is the first "(?s)", itself a passthrough -- exercises chaining
    // through two synthesized matcher/entryMap aliases in a row.
    assertThat(Ll1Pattern.compile("(?s)(?s)abx").matcher("abx").matches(), is(true));
  }

  // --- FIXED (2026-09-06): inline "(?i:...)" only ever mutated PatternParser's own `flags` field
  // at parse time (affecting every construct parsed after it in the current scope), never actually
  // scoping case-insensitivity to just the group at match time -- so "(?i:abc)def" wrongly made
  // "def" case-insensitive too. See remaining_work.md/README.md.

  @Test
  public void inlineFlagGroup_scopesCaseInsensitivityToJustTheGroup() {
    Ll1Pattern p = Ll1Pattern.compile("(?i:abc)def");
    assertThat(p.matcher("ABCdef").matches(), is(true));
    assertThat(p.matcher("ABCDEF").matches(), is(false));
    assertThat(p.matcher("abcDEF").matches(), is(false));
  }

  @Test
  public void inlineFlagGroup_doesNotLeakIntoWhatCameBeforeIt() {
    Ll1Pattern p = Ll1Pattern.compile("abc(?i:def)");
    assertThat(p.matcher("ABCdef").matches(), is(false));
    assertThat(p.matcher("abcDEF").matches(), is(true));
  }

  @Test
  public void inlineFlagGroup_restoresOuterScopeAfterClosing() {
    Ll1Pattern p = Ll1Pattern.compile("(?i:abc)DEF");
    assertThat(p.matcher("ABCDEF").matches(), is(true));
    assertThat(p.matcher("ABCdef").matches(), is(false));
  }

  // Literal-string matching (above) goes through LiteralMatcherConstruct#match's
  // codePointsMatch(...) call -- a char class instead exercises getNext()'s dispatch-map
  // fold-retry path (SingleCharMatcherConstruct), the other of the two places local `flags` had
  // to be threaded through. Both need their own coverage since they're separate code paths.
  @Test
  public void inlineFlagGroup_scopesCharClassCaseInsensitivityToJustTheGroup() {
    Ll1Pattern p = Ll1Pattern.compile("(?i:[a-z]+)X");
    assertThat(p.matcher("ABCX").matches(), is(true));
    assertThat(p.matcher("ABCx").matches(), is(false));
  }

  // Sanity check that the global (non-inline) CASE_INSENSITIVE path still reaches a \w-style
  // named-escape ComplexCharacter and a negated [^...] class -- two of the several separate
  // ComplexCharacter construction sites PatternParser now has to individually stamp with the
  // parser's current `flags` (see PatternConstruct#flags's doc) now that MatcherConstruct reads a
  // per-node local flags snapshot instead of the pattern-wide Ll1Pattern.compile flags.
  @Test
  public void globalCaseInsensitive_namedEscapeClass_stillWorks() {
    assertThat(
        Ll1Pattern.compile("\\w+", Ll1Pattern.CASE_INSENSITIVE).matcher("ABC").matches(),
        is(true));
  }

  @Test
  public void globalCaseInsensitive_negatedCharClass_stillWorks() {
    assertThat(
        Ll1Pattern.compile("[^0-9]+", Ll1Pattern.CASE_INSENSITIVE).matcher("ABC").matches(),
        is(true));
  }
}
