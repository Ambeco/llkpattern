package com.tbohne.llkpattern.impl.unicode;

import java.util.regex.Pattern;

/**
 * The flag bits that change Unicode semantics, defined here so {@code impl.unicode} depends on nothing
 * above it.
 *
 * <p>The values equal {@link Pattern}'s, and {@code Ll1Pattern}'s public constants of the same names are
 * initialized from these, so all three stay in step.
 */
public final class UnicodeFlags {
  public static final int CANON_EQ = Pattern.CANON_EQ;
  public static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;
  public static final int UNICODE_CASE = Pattern.UNICODE_CASE;
  public static final int UNICODE_CHARACTER_CLASS = Pattern.UNICODE_CHARACTER_CLASS;

  private UnicodeFlags() {}
}
