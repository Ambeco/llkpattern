package com.tbohne.llkpattern.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One row of a scraped-corpus golden file: a (pattern, flags, input, mode) tuple that some other
 * project's regex test suite exercised, plus the recorded outcome of running it through both
 * {@code java.util.regex} ("regex*" columns) and {@code Ll1Pattern} ("llk*" columns).
 *
 * <p>Column order (tab-separated, one row per line, header row first):
 *
 * <pre>
 * pattern  flags  input  mode  regexCompileException  regexMatchException  regexMatchResult
 * llkCompileException  llkMatchException  llkMatchResult  status  originalPathologicalInput
 * </pre>
 *
 * <p>{@code status} is free-text triage metadata (e.g. {@code "AGREES"}, {@code
 * "EXPECTED_DIVERGENCE: ..."}, {@code "UNIMPLEMENTED: ..."}, {@code "UNEXPECTED: ..."}) — it is
 * NOT used as a test parameter or compared by the test; it exists purely so a human (or a future
 * pass) can see at a glance why a row's regex/llk columns differ, since TSV/JSON have no comment
 * syntax. See documents/remaining_work.md for the full scheme.
 *
 * <p>{@code originalPathologicalInput} is {@code ""} (the common case) unless {@link #input} is a
 * *simplified* stand-in for an input the scraped source actually used, because the original
 * caused catastrophic backtracking in {@code java.util.regex} (some source suites deliberately
 * test ReDoS-prone patterns against long adversarial inputs -- which is exactly what we don't
 * want to pay for on every generator/test run, since the point of this corpus is comparing match
 * *behavior*, not re-benchmarking java.util.regex's backtracking worst case). When non-empty, it
 * holds the real original input the source used, so the substitution is traceable rather than a
 * silent edit. See {@link CorpusGenerator}'s timeout/simplification logic and
 * documents/remaining_work.md.
 */
public final class GoldenRow {
  public enum Mode {
    MATCHES,
    LOOKING_AT,
    FIND
  }

  public final String pattern;
  public final String flags; // comma-separated Ll1Pattern/Pattern flag constant names, e.g. "CASE_INSENSITIVE,MULTILINE"
  public final String input;
  public final Mode mode;
  public final String regexCompileException; // exception simple class name, or "" if compile succeeded
  public final String regexMatchException; // exception simple class name, or "" if no exception
  public final String regexMatchResult; // encoded MatchOutcome, or "" if either exception column is non-empty
  public final String llkCompileException;
  public final String llkMatchException;
  public final String llkMatchResult;
  public final String status;
  /** "" unless {@link #input} was simplified from a pathological original -- see the class
   *  javadoc. */
  public final String originalPathologicalInput;

  public GoldenRow(
      String pattern,
      String flags,
      String input,
      Mode mode,
      String regexCompileException,
      String regexMatchException,
      String regexMatchResult,
      String llkCompileException,
      String llkMatchException,
      String llkMatchResult,
      String status,
      String originalPathologicalInput) {
    this.pattern = pattern;
    this.flags = flags;
    this.input = input;
    this.mode = mode;
    this.regexCompileException = regexCompileException;
    this.regexMatchException = regexMatchException;
    this.regexMatchResult = regexMatchResult;
    this.llkCompileException = llkCompileException;
    this.llkMatchException = llkMatchException;
    this.llkMatchResult = llkMatchResult;
    this.status = status;
    this.originalPathologicalInput = originalPathologicalInput;
  }

  public int flagBits() {
    int bits = 0;
    if (flags.isEmpty()) {
      return 0;
    }
    for (String name : flags.split(",")) {
      bits |= flagBit(name);
    }
    return bits;
  }

  private static int flagBit(String name) {
    switch (name) {
      case "CANON_EQ":
        return java.util.regex.Pattern.CANON_EQ;
      case "CASE_INSENSITIVE":
        return java.util.regex.Pattern.CASE_INSENSITIVE;
      case "COMMENTS":
        return java.util.regex.Pattern.COMMENTS;
      case "DOTALL":
        return java.util.regex.Pattern.DOTALL;
      case "LITERAL":
        return java.util.regex.Pattern.LITERAL;
      case "MULTILINE":
        return java.util.regex.Pattern.MULTILINE;
      case "UNICODE_CASE":
        return java.util.regex.Pattern.UNICODE_CASE;
      case "UNICODE_CHARACTER_CLASS":
        return java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;
      case "UNIX_LINES":
        return java.util.regex.Pattern.UNIX_LINES;
      default:
        throw new IllegalArgumentException(
            "Unknown regex flag name '" + name + "' -- did you mean to add it to "
                + "GoldenRow.flagBit()/flagBits()? Known names: CANON_EQ, CASE_INSENSITIVE, "
                + "COMMENTS, DOTALL, LITERAL, MULTILINE, UNICODE_CASE, "
                + "UNICODE_CHARACTER_CLASS, UNIX_LINES.");
    }
  }

  /** A one-line display name for this row, used as the parameterized test's case name. */
  public String displayName() {
    return mode + " /" + truncate(pattern) + "/" + (flags.isEmpty() ? "" : "(" + flags + ")")
        + " vs \"" + truncate(input) + "\"";
  }

  private static String truncate(String s) {
    String oneLine = s.replace("\n", "\\n").replace("\t", "\\t");
    return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 37) + "...";
  }

  List<String> toFields() {
    List<String> fields = new ArrayList<>(11);
    fields.add(pattern);
    fields.add(flags);
    fields.add(input);
    fields.add(mode.name());
    fields.add(regexCompileException);
    fields.add(regexMatchException);
    fields.add(regexMatchResult);
    fields.add(llkCompileException);
    fields.add(llkMatchException);
    fields.add(llkMatchResult);
    fields.add(status);
    fields.add(originalPathologicalInput);
    return fields;
  }

  static GoldenRow fromFields(List<String> fields) {
    if (fields.size() != 12) {
      throw new IllegalArgumentException(
          "Golden row has " + fields.size() + " fields, expected 12 "
              + "(pattern, flags, input, mode, regexCompileException, regexMatchException, "
              + "regexMatchResult, llkCompileException, llkMatchException, llkMatchResult, "
              + "status, originalPathologicalInput) -- was this file hand-edited with a wrong "
              + "tab count? Fields: " + fields);
    }
    Mode mode;
    try {
      mode = Mode.valueOf(fields.get(3));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown mode '" + fields.get(3) + "' -- did you mean one of "
              + java.util.Arrays.toString(Mode.values()) + "?",
          e);
    }
    return new GoldenRow(
        fields.get(0),
        fields.get(1),
        fields.get(2),
        mode,
        fields.get(4),
        fields.get(5),
        fields.get(6),
        fields.get(7),
        fields.get(8),
        fields.get(9),
        fields.get(10),
        fields.get(11));
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof GoldenRow)) return false;
    GoldenRow other = (GoldenRow) o;
    return pattern.equals(other.pattern)
        && flags.equals(other.flags)
        && input.equals(other.input)
        && mode == other.mode
        && regexCompileException.equals(other.regexCompileException)
        && regexMatchException.equals(other.regexMatchException)
        && regexMatchResult.equals(other.regexMatchResult)
        && llkCompileException.equals(other.llkCompileException)
        && llkMatchException.equals(other.llkMatchException)
        && llkMatchResult.equals(other.llkMatchResult)
        && status.equals(other.status)
        && originalPathologicalInput.equals(other.originalPathologicalInput);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        pattern, flags, input, mode, regexCompileException, regexMatchException,
        regexMatchResult, llkCompileException, llkMatchException, llkMatchResult, status,
        originalPathologicalInput);
  }

  @Override
  public String toString() {
    return displayName();
  }
}
