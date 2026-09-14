package com.tbohne.llkpattern;

import static com.tbohne.llkpattern.SupplementaryChars.A;
import static com.tbohne.llkpattern.SupplementaryChars.B;
import static com.tbohne.llkpattern.SupplementaryChars.C;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * \G doesn't match any specific position in the input -- unlike every other boundary, it has no
 * PatternConstruct/MatcherConstruct representation at all. It's purely a flag (see
 * PatternParser#anchorsToPreviousMatchEnd) telling Matcher#find() to anchor to exactly where the
 * previous match ended (Matcher#matchEnd, already tracked for group(0)/end()) instead of scanning
 * forward for a later match -- and it's only meaningful there, since matches()/lookingAt() never
 * scan in the first place. Only allowed as the very first thing in the whole pattern; see
 * design.md's "Boundary matching" section.
 *
 * <p>{@code ABC} (a 3-code-point supplementary literal, replacing the plain ASCII "abc" this file
 * originally used) is 6 {@code char} units long, not 3 -- every {@code start()}/{@code end()}
 * offset assertion below accounts for that.
 */
@RunWith(JUnit4.class)
public class PreviousMatchEndTest {
  private static final String ABC = A + B + C;

  @Test
  public void find_repeatedly_onlyMatchesContiguousRuns() {
    Matcher m = Ll1Pattern.compile("\\G" + ABC).matcher(ABC + ABC + "x" + ABC);
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(0));
    assertThat(m.end(), is(ABC.length()));
    assertThat(m.find(), is(true));
    assertThat(m.start(), is(ABC.length()));
    assertThat(m.end(), is(2 * ABC.length()));
    // Position 2*ABC.length() is 'x', not ABC -- \G forbids scanning ahead to the ABC right after
    // it, so this find() must fail even though a later match exists.
    assertThat(m.find(), is(false));
  }

  @Test
  public void find_explicitStart_alsoOnlyTriesExactlyThere() {
    Matcher m = Ll1Pattern.compile("\\G" + ABC).matcher("x" + ABC);
    assertThat(m.find(1), is(true));
    assertThat(m.start(), is(1));
    Matcher m2 = Ll1Pattern.compile("\\G" + ABC).matcher("xx" + ABC);
    assertThat(m2.find(1), is(false)); // ABC is at position 2, not 1 -- must not scan to it.
  }

  @Test
  public void matches_and_lookingAt_areUnaffected() {
    // Neither ever scans for a later start position, so \G has nothing to change there.
    assertThat(Ll1Pattern.compile("\\G" + ABC).matcher(ABC).matches(), is(true));
    assertThat(Ll1Pattern.compile("\\G" + ABC).matcher(ABC + "x").lookingAt(), is(true));
  }

  @Test
  public void onlyAllowedAsTheVeryFirstThingInThePattern() {
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile(A + "\\G" + B));
    assertThrows(PatternSyntaxException.class, () -> Ll1Pattern.compile("(\\G" + ABC + ")"));
  }
}
