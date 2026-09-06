package com.tbohne.llkpattern.corpus;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Round-trip tests for {@link GoldenTsv}'s field escaping and {@link MatchOutcome}'s encoding --
 *  written before any real corpus data, per the plan in remaining_work.md, precisely because a
 *  codec bug would otherwise be indistinguishable from a real engine divergence. */
@RunWith(JUnit4.class)
public class GoldenTsvTest {

  @Test
  public void fieldCodec_roundTripsPlainText() {
    assertRoundTrips("hello world");
    assertRoundTrips("");
  }

  @Test
  public void fieldCodec_roundTripsTabsNewlinesAndBackslashes() {
    assertRoundTrips("a\tb\nc\rd\\e");
    assertRoundTrips("\\\\\\t\\n");
    assertRoundTrips("trailing backslash before escape: \\\\");
  }

  @Test
  public void fieldCodec_roundTripsUnicodeAndControlChars() {
    assertRoundTrips("あぃ emoji: 😀");
    assertRoundTrips("unit separator:  end");
  }

  @Test
  public void fieldCodec_roundTripsLoneSurrogates() {
    // A lone high surrogate with no low surrogate following -- e.g. OpenJDK's
    // SupplementaryTestCases.txt deliberately includes these (see CorpusGenerator.unescapeOpenJdk).
    assertRoundTrips("a\uD800b");
    // A lone low surrogate with no high surrogate preceding.
    assertRoundTrips("a\uDC00b");
    // A *valid* surrogate pair must NOT be escaped (it's representable in UTF-8 as-is).
    String validPair = "a" + "𐀀" + "b";
    String encoded = GoldenTsv.encodeField(validPair);
    assertThat("a valid surrogate pair should pass through unescaped", encoded, is(validPair));
    assertRoundTrips(validPair);
  }

  @Test
  public void fieldCodec_decodeRejectsUnknownEscape() {
    try {
      GoldenTsv.decodeField("\\q");
      throw new AssertionError("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage().contains("\\q"), is(true));
    }
  }

  private static void assertRoundTrips(String s) {
    String encoded = GoldenTsv.encodeField(s);
    assertThat("encoded field must not contain a raw tab/CR/LF", encoded.indexOf('\t'), is(-1));
    assertThat(encoded.indexOf('\n'), is(-1));
    assertThat(encoded.indexOf('\r'), is(-1));
    assertThat(GoldenTsv.decodeField(encoded), is(s));
  }

  @Test
  public void matchOutcome_roundTripsNoMatch() {
    assertOutcomeRoundTrips(MatchOutcome.noMatch());
  }

  @Test
  public void matchOutcome_roundTripsMatchWithNullAndEmptyGroups() {
    assertOutcomeRoundTrips(MatchOutcome.matched(Arrays.asList("whole", null, "", "a\tb\nc")));
  }

  @Test
  public void matchOutcome_roundTripsCompileFailure() {
    RuntimeException e = new java.util.regex.PatternSyntaxException("bad", "x", 0);
    MatchOutcome outcome = MatchOutcome.compileFailure(e);
    MatchOutcome decoded = MatchOutcome.decode(outcome.compileException, "", outcome.encodeResult());
    assertThat(decoded, is(outcome));
  }

  @Test
  public void matchOutcome_roundTripsMatchFailure() {
    RuntimeException e = new UnsupportedOperationException("nope");
    MatchOutcome outcome = MatchOutcome.matchFailure(e);
    MatchOutcome decoded = MatchOutcome.decode("", outcome.matchException, outcome.encodeResult());
    assertThat(decoded, is(outcome));
  }

  private static void assertOutcomeRoundTrips(MatchOutcome outcome) {
    MatchOutcome decoded =
        MatchOutcome.decode(
            outcome.compileException, outcome.matchException, outcome.encodeResult());
    assertThat(decoded, is(outcome));
  }

  @Test
  public void tsv_roundTripsALoneSurrogateThroughAFile() throws IOException {
    // Regression test: GoldenTsv.write used to hand raw lone-surrogate chars straight to a UTF-8
    // Writer, which throws MalformedInputException -- a lone surrogate isn't a valid Unicode
    // scalar value, so UTF-8 has no way to encode it -- discovered generating the OpenJDK
    // SupplementaryTestCases.txt golden file (2026-09-06).
    GoldenRow row =
        new GoldenRow(
            "pattern with a lone surrogate: \uD800 end",
            "",
            "input with a lone surrogate: \uDC00 end",
            GoldenRow.Mode.FIND,
            "",
            "",
            "NOMATCH",
            "",
            "",
            "NOMATCH",
            "AGREES",
            "");
    Path tmp = Files.createTempFile("golden-tsv-surrogate-test", ".tsv");
    try {
      GoldenTsv.write(tmp, List.of(row));
      List<GoldenRow> readBack = GoldenTsv.read(tmp);
      assertThat(readBack, is(List.of(row)));
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Test
  public void tsv_roundTripsRowsThroughAFile() throws IOException {
    GoldenRow row =
        new GoldenRow(
            "a\tb\\c\nd",
            "CASE_INSENSITIVE,MULTILINE",
            "input\twith\ttabs",
            GoldenRow.Mode.FIND,
            "",
            "",
            "MATCHwhole group2",
            "PatternSyntaxException",
            "",
            "",
            "UNIMPLEMENTED: example",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!");
    Path tmp = Files.createTempFile("golden-tsv-test", ".tsv");
    try {
      GoldenTsv.write(tmp, List.of(row));
      List<GoldenRow> readBack = GoldenTsv.read(tmp);
      assertThat(readBack.size(), is(1));
      assertThat(readBack.get(0), is(row));
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
