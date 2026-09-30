package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Two line-end ({@code $}/{@code \Z}) rules found by {@link DotElseDifferentialTest}, checked here
 * without any {@code .} in the pattern:
 * <ul>
 *   <li>the halves of a {@code "\r\n"} pair are one terminator, so {@code $}/{@code \Z} never hold
 *       between them;
 *   <li>under {@code UNIX_LINES} only {@code \n} is a terminator, so a MULTILINE {@code $} does not
 *       hold before a {@code \r} and does not stop a loop that could consume it.
 * </ul>
 */
@RunWith(JUnit4.class)
public class LineEndCrLfUnixLinesTest {
  private static final String[] PATTERNS = {"\\r$", "[\\r\\n]$","[\\r]\\Z", "b*$", "[^x]$"};
  private static final String[] INPUTS = {"\r\n", "a\r\n", "a\r\nb", "\r", "\n", "a\r", "a\n", "\n\r"};
  private static final int[] FLAG_SETS = {
    0, Pattern.MULTILINE, Pattern.UNIX_LINES, Pattern.MULTILINE | Pattern.UNIX_LINES
  };

  @Test
  public void lineEndAssertions_matchJavaUtilRegex_atCrLfPairs() {
    List<String> divergences = new ArrayList<>();
    for (String p : PATTERNS) {
      for (int flags : FLAG_SETS) {
        Pattern jdk = Pattern.compile(p, flags);
        Ll1Pattern llk = Ll1Pattern.compile(p, flags);
        for (String in : INPUTS) {
          java.util.regex.Matcher jm = jdk.matcher(in);
          Matcher lm = llk.matcher(in);
          boolean jFound = jm.find();
          boolean lFound = lm.find();
          String ctx = "/" + p + "/ flags " + flags + " on " + escape(in);
          if (jFound != lFound || (jFound && (jm.start() != lm.start() || jm.end() != lm.end()))) {
            divergences.add(ctx + ": jdk=" + (jFound ? jm.start() + "," + jm.end() : "none")
                + " llk=" + (lFound ? lm.start() + "," + lm.end() : "none"));
          }
        }
      }
    }
    assertThat(divergences.toString(), divergences.isEmpty(), is(true));
  }

  @Test
  public void multilineDollarAfterLoopOverCr_compilesUnderUnixLines() {
    // `\r` is an ordinary character under UNIX_LINES, so the loop and the `$` exit don't overlap.
    int flags = Pattern.MULTILINE | Pattern.UNIX_LINES;
    Matcher m = Ll1Pattern.compile("[^\\n]+$", flags).matcher("a\r\nb");
    assertThat(m.find(), is(true));
    assertThat(m.group(), is("a\r"));
  }

  private static String escape(String s) {
    return s.replace("\r", "\\r").replace("\n", "\\n");
  }
}
