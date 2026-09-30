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
 * {@code .} means "all other options": inside a branching context it claims whatever its siblings
 * (and a loop's exit) do not (README.md, design.md's "Dot" section). The oracle for each pattern
 * below is therefore NOT the same text handed to java.util.regex (which would backtrack) but the
 * pattern with {@code .} rewritten to its explicit residual class; {@code @} in the oracle text
 * expands to the line terminators {@code .} excludes under the flag set (nothing under DOTALL) and
 * {@code #} to the ones a MULTILINE {@code $} stops at (regardless of DOTALL). Patterns where
 * {@code .} has no sibling to conflict with use themselves as the oracle. Where the explicit
 * pattern needs backtracking that llk deliberately doesn't do, the oracle is llk's own compile of
 * the explicit pattern instead (marked {@code "llk"}), which still isolates the dot mechanism.
 */
@RunWith(JUnit4.class)
public class DotElseDifferentialTest {
  private static final String[][] PAIRS = {
    // {llk pattern, oracle pattern[, "llk" if the oracle is llk itself]}
    {"a|.", "a|[^a@]"},
    {".|a", "[^a@]|a"},
    {"a|.b", "a|[^a@]b"},
    {"\\n|.", "\\n|[^\\n@]"},
    {"(?i)a|.", "(?i)a|[^a@]"},
    {".+b", "[^b@]+b"},
    {".*b", "[^b@]*b"},
    {".*?b", "[^b@]*?b"},
    {".+?b", "[^b@]+?b"},
    {"a.+b", "a[^b@]+b"},
    {"a.?b", "a[^b@]?b"},
    {".{2,3}b", "[^b@]{2,3}b"},
    {"(.b|c)+", "([^c@]b|c)+", "llk"},
    {"(.)+b", "([^b@])+b"},
    {"(.+)b", "([^b@]+)b"},
    {"(?:.b)+c", "(?:[^c@]b)+c"},
    {"a*.", "a*[^a@]"},
    {"a+.b", "a+[^a@]b"},
    {".*x?", "[^x@]*x?"},
    {".+a?", "[^a@]+a?"},
    {"(a|.)b", "(a|[^a@])b"},
    {"(?:a|.)+b", "(?:a|[^ab@])+b"},
    // other sibling kinds: classes, named classes, deeper nesting, reluctant/optional shapes
    {"[ab]|.", "[ab]|[^ab@]"},
    {"\\w|.", "\\w|[^\\w@]"},
    {"a|b|.", "a|b|[^ab@]"},
    {"a(b|.)", "a(b|[^b@])"},
    {"(a|(b|.))", "(a|(b|[^ab@]))"},
    {"(?:a(?:b|.)|c)", "(?:a(?:b|[^b@])|c)"},
    {"x(?:.|\\n)y", "x(?:[^\\n@]|\\n)y"},
    {"(a|.)*b", "(a|[^ab@])*b"},
    {"(?:a|.)+?b", "(?:a|[^ab@])+?b"},
    {"(.)*?b", "([^b@])*?b"},
    {".??b", "[^b@]??b"},
    {".?b", "[^b@]?b"},
    {"(.?)b", "([^b@]?)b"},
    {"(?:.b)*c", "(?:[^c@]b)*c"},
    // after a `?` there is no back edge, so a consumed `a` leaves `.` unopposed
    {"a?.", "(?:a.|[^a@])"},
    {"a*.b*", "a*[^a@]b*"},
    {"[ab]*.", "[ab]*[^ab@]"},
    {"\\s*.", "\\s*[^\\s@]"},
    {"\\d+.", "\\d+[^\\d@]"},
    {"b|.*c", "b|(?!b)[^c@]*c"},
    {".+\\n", "[^\\n@]+\\n"},
    {"(?i)a.+B", "(?i)a[^b@]+B"},
    // no conflict: `.` is the only claimant, so it behaves as an ordinary "any character"
    {".", "."},
    {".*", ".*"},
    {".+", ".+"},
    {"(.*)", "(.*)"},
    {"^.*$", "^.*$"},
    {".*$", ".*$"},
    {"a.", "a."},
    {"a.?", "a.?"},
    {".a", ".a"},
    {"..", ".."},
    {"(.)(.)", "(.)(.)"},
    {"a.*", "a.*"},
    {".$", ".$"},
    {"a.$", "a.$"},
    {".\\b", ".\\b"},
    {"a.\\z", "a.\\z"},
    {".\\Z", ".\\Z"},
    {"(.)?", "(.)?"},
    {"(.?)", "(.?)"},
    {"(?:.)+", "(?:.)+"},
  };

  /** Pairs whose oracle depends on the MULTILINE terminator set ({@code #}); run only with it. */
  private static final String[][] MULTILINE_PAIRS = {
    {".+$", "[^#]+$"},
    {".*$", "[^#]*$"},
    {"^.+$", "^[^#]+$"},
    {"^.*b$", "^[^b@]*b$"},
    {"a|.$", "a|[^a@]$"},
    {"(?:.$|a)", "(?:[^a@]$|a)"},
    {".*", ".*"},
    {"^.*", "^.*"},
  };

  private static final int[] FLAG_SETS = {
    0, Ll1Pattern.DOTALL, Ll1Pattern.UNIX_LINES, Ll1Pattern.CASE_INSENSITIVE,
    Ll1Pattern.DOTALL | Ll1Pattern.UNIX_LINES
  };
  private static final int[] MULTILINE_FLAG_SETS = {
    Ll1Pattern.MULTILINE, Ll1Pattern.MULTILINE | Ll1Pattern.DOTALL,
    Ll1Pattern.MULTILINE | Ll1Pattern.UNIX_LINES,
    Ll1Pattern.MULTILINE | Ll1Pattern.DOTALL | Ll1Pattern.UNIX_LINES
  };
  private static final char[] ALPHABET = {'a', 'b', 'c', 'x', '\n'};

  /** The two engines' matchers behind one interface, so either can be the oracle. */
  private interface M {
    void region(int s, int e);
    boolean run(int op);
    boolean hitEnd();
    boolean requireEnd();
    int start();
    int end();
    int groupCount();
    int start(int g);
    int end(int g);
  }

  private static M wrap(java.util.regex.Matcher m) {
    return new M() {
      public void region(int s, int e) { m.region(s, e); }
      public boolean run(int op) { return op == 0 ? m.matches() : op == 1 ? m.lookingAt() : m.find(); }
      public boolean hitEnd() { return m.hitEnd(); }
      public boolean requireEnd() { return m.requireEnd(); }
      public int start() { return m.start(); }
      public int end() { return m.end(); }
      public int groupCount() { return m.groupCount(); }
      public int start(int g) { return m.start(g); }
      public int end(int g) { return m.end(g); }
    };
  }

  private static M wrap(Matcher m) {
    return new M() {
      public void region(int s, int e) { m.region(s, e); }
      public boolean run(int op) { return op == 0 ? m.matches() : op == 1 ? m.lookingAt() : m.find(); }
      public boolean hitEnd() { return m.hitEnd(); }
      public boolean requireEnd() { return m.requireEnd(); }
      public int start() { return m.start(); }
      public int end() { return m.end(); }
      public int groupCount() { return m.groupCount(); }
      public int start(int g) { return m.start(g); }
      public int end(int g) { return m.end(g); }
    };
  }

  private static String terminators(int flags) {
    if ((flags & Ll1Pattern.DOTALL) != 0) {
      return "";
    }
    return multilineTerminators(flags);
  }

  private static String multilineTerminators(int flags) {
    return (flags & Ll1Pattern.UNIX_LINES) != 0 ? "\\n" : "\\n\\r\\u0085\\u2028\\u2029";
  }

  private static List<String> inputs() {
    List<String> result = new ArrayList<>();
    result.add("");
    List<String> prev = new ArrayList<>();
    prev.add("");
    for (int len = 1; len <= 3; len++) {
      List<String> next = new ArrayList<>();
      for (String s : prev) {
        for (char c : ALPHABET) {
          next.add(s + c);
        }
      }
      result.addAll(next);
      prev = next;
    }
    result.add("abxab");
    result.add("aab\nb");
    result.add("xxbxb");
    result.add("a\r\nb");
    result.add("b\u2028c");
    result.add("\uD801\uDC00b");
    result.add("a\uD801\uDC00b");
    return result;
  }

  @Test
  public void everyPairCompilesUnderEveryFlagSet() {
    List<String> failures = new ArrayList<>();
    for (String[] pair : PAIRS) {
      for (int flags : FLAG_SETS) {
        try {
          Ll1Pattern.compile(pair[0], flags);
        } catch (RuntimeException e) {
          failures.add("/" + pair[0] + "/ flags " + flags + ": " + e.getMessage());
        }
      }
    }
    assertThat(failures.toString(), failures.isEmpty(), is(true));
  }

  @Test
  public void twoResidualClaimantsAreStillAmbiguous() {
    String[] ambiguous = {".|.", ".b|.c", "(?:.a|.)b", ".*.", "(.)*.+", ".+."};
    for (String p : ambiguous) {
      for (int flags : FLAG_SETS) {
        try {
          Ll1Pattern.compile(p, flags);
          throw new AssertionError("expected /" + p + "/ (flags " + flags + ") to be ambiguous");
        } catch (PatternSyntaxException expected) {
          // expected
        }
      }
    }
  }

  @Test
  public void possessiveDotLoop_swallowingALaterPart_isRejectedButPlainOnesCompile() {
    // java.util.regex never gives characters back from a possessive loop, so these can never match
    // there; compiling them as "[^b]++b" would be a silent divergence -- reject like a real ambiguity.
    for (String p : new String[] {".++b", ".*+b", "(?:.)++b", "(?:.|a)*+b", "a|.++b"}) {
      try {
        Ll1Pattern.compile(p);
        throw new AssertionError("expected /" + p + "/ to be rejected");
      } catch (PatternSyntaxException expected) {
        // expected
      }
    }
    // Nothing for it to swallow: fine, and identical to java.util.regex.
    for (String p : new String[] {".++", ".*+", "a.++", "(?:.)++"}) {
      Ll1Pattern llk = Ll1Pattern.compile(p);
      Pattern jdk = Pattern.compile(p);
      for (String in : new String[] {"", "a", "abc", "ab\ncd"}) {
        assertThat("/" + p + "/ on " + in, llk.matcher(in).matches(), is(jdk.matcher(in).matches()));
        assertThat("/" + p + "/ on " + in, llk.matcher(in).lookingAt(), is(jdk.matcher(in).lookingAt()));
      }
    }
  }

  @Test
  public void dispatchAndCaptureHandChecks_stillHold() {
    // CLAUDE.md's two hand-checks for any dispatch/capture change.
    assertThat(Ll1Pattern.compile("((a?b)c)?").matcher("").matches(), is(true));
    Matcher m = Ll1Pattern.compile("(a+b)+").matcher("ababab");
    assertThat(m.matches(), is(true));
    assertThat(m.group(1), is("ab"));
    // ... and the same shapes with a residual `.` in them.
    assertThat(Ll1Pattern.compile("((.?b)c)?").matcher("").matches(), is(true));
    Matcher d = Ll1Pattern.compile("(.+b)+").matcher("ababab");
    assertThat(d.matches(), is(true));
    assertThat(d.group(1), is("ab"));
  }

  @Test
  public void matchesLookingAtAndFind_matchExplicitResidualOracle() {
    assertNoDivergences(PAIRS, FLAG_SETS);
  }

  @Test
  public void multilineDollarPairs_matchExplicitResidualOracle() {
    assertNoDivergences(MULTILINE_PAIRS, MULTILINE_FLAG_SETS);
  }

  /** A region boundary inside a surrogate pair or a CR LF pair is outside what the engines agree
   *  on (and is about `$`/`\Z`/pair handling, which other tests cover, not about `.`). */
  private static boolean splitsSurrogatePair(String in, int index) {
    if (index <= 0 || index >= in.length()) {
      return false;
    }
    char before = in.charAt(index - 1);
    char after = in.charAt(index);
    return (Character.isHighSurrogate(before) && Character.isLowSurrogate(after))
        || (before == 0x0D && after == 0x0A);
  }

  private static void assertNoDivergences(String[][] pairs, int[] flagSets) {
    List<String> divergences = new ArrayList<>();
    List<String> inputs = inputs();
    for (String[] pair : pairs) {
      boolean llkOracle = pair.length > 2;
      for (int flags : flagSets) {
        String oracleText = pair[1].replace("@", terminators(flags)).replace("#", multilineTerminators(flags));
        Pattern jdkOracle = llkOracle ? null : Pattern.compile(oracleText, flags);
        Ll1Pattern llkOracleP = llkOracle ? Ll1Pattern.compile(oracleText, flags) : null;
        Ll1Pattern llk = Ll1Pattern.compile(pair[0], flags);
        for (String in : inputs) {
          for (int s = 0; s <= in.length(); s++) {
            if (splitsSurrogatePair(in, s)) {
              continue;
            }
            for (int e = s; e <= in.length(); e++) {
              if (splitsSurrogatePair(in, e)) {
                continue;
              }
              for (int op = 0; op < 3; op++) {
                M jm = llkOracle ? wrap(llkOracleP.matcher(in)) : wrap(jdkOracle.matcher(in));
                M lm = wrap(llk.matcher(in));
                jm.region(s, e);
                lm.region(s, e);
                boolean jr = jm.run(op);
                boolean lr = lm.run(op);
                String ctx = "op" + op + " /" + pair[0] + "/ flags " + flags + " on \""
                    + in.replace("\n", "\\n").replace("\r", "\\r") + "\" region [" + s + "," + e + ")";
                if (jr != lr) {
                  divergences.add(ctx + ": result oracle=" + jr + " llk=" + lr);
                  continue;
                }
                if (jm.hitEnd() != lm.hitEnd()) {
                  divergences.add(ctx + ": hitEnd oracle=" + jm.hitEnd() + " llk=" + lm.hitEnd());
                }
                if (!jr) {
                  continue;
                }
                if (jm.start() != lm.start() || jm.end() != lm.end()) {
                  divergences.add(ctx + ": span oracle=[" + jm.start() + "," + jm.end() + ") llk=["
                      + lm.start() + "," + lm.end() + ")");
                  continue;
                }
                if (jm.requireEnd() != lm.requireEnd()) {
                  divergences.add(ctx + ": requireEnd oracle=" + jm.requireEnd() + " llk="
                      + lm.requireEnd());
                }
                for (int g = 1; g <= jm.groupCount(); g++) {
                  if (jm.start(g) != lm.start(g) || jm.end(g) != lm.end(g)) {
                    divergences.add(ctx + ": group " + g + " oracle=[" + jm.start(g) + ","
                        + jm.end(g) + ") llk=[" + lm.start(g) + "," + lm.end(g) + ")");
                    break;
                  }
                }
              }
            }
          }
        }
      }
    }
    // Per-pattern counts plus a few samples of each, so one noisy pattern can't hide the rest.
    java.util.Map<String, List<String>> byPattern = new java.util.LinkedHashMap<>();
    for (String d : divergences) {
      String key = d.substring(0, d.indexOf(" flags "));
      byPattern.computeIfAbsent(key.substring(key.indexOf(' ') + 1), k -> new ArrayList<>()).add(d);
    }
    StringBuilder message = new StringBuilder(divergences.size() + " divergences\n");
    for (java.util.Map.Entry<String, List<String>> e : byPattern.entrySet()) {
      message.append(e.getValue().size()).append(" ").append(e.getKey()).append("\n");
      for (String d : e.getValue().subList(0, Math.min(12, e.getValue().size()))) {
        message.append("    ").append(d).append("\n");
      }
    }
    assertThat(message.toString(), divergences.isEmpty(), is(true));
  }
}
