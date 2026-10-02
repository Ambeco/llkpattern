package com.tbohne.llkpattern.corpus;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RowBucketsTest {
  private static void check(String expected, String pattern, String flags) {
    assertEquals("pattern: " + pattern, expected, RowBuckets.bucketOf(pattern, flags));
  }

  @Test
  public void priorityOrderPicksTheMostSpecialisedFeature() {
    check("lookaround", "a(?=b)c+", "");
    check("lookaround", "(?<!x)[a-z]", "");
    check("backref", "(a)\\1", "");
    check("backref", "(?<n>a)\\k<n>", "");
    check("unicode-feature", "\\p{L}+", "");
    check("unicode-feature", "\\X", "");
    check("flags", "abc", "CASE_INSENSITIVE");
    check("flags", "(?i)abc", "");
    check("non-ascii", "[日本]+", "");
    check("word-boundary", "\\bfoo\\b", "");
    check("char-class", "[a-z]+", "");
    check("alternation", "a|b", "");
    check("group", "(ab)", "");
    check("quantifier", "ab*", "");
    check("anchor", "^abc$", "");
    check("literal", "abc", "");
  }
}
