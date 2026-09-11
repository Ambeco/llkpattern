package com.tbohne.llkpattern.corpus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Generates (or regenerates) a golden TSV file from an "intermediate" TSV of scraped tuples.
 *
 * <p>Input format (one line per tuple, tab-separated, no header, produced by a per-source scraper
 * such as {@code ./tools/scrape_openjdk_regex.py}): {@code pattern\tflags\tinput[\tmode]}.
 * Unlike the golden file itself, intermediate fields are NOT GoldenTsv-escaped -- they're the raw
 * text a scraper pulled straight out of a source file's lines (which never contain real tabs or
 * newlines), optionally still carrying that source's own escaping convention (e.g. OpenJDK's
 * grabLine()-style {@code \n}/{@code \backslash-u-XXXX}), to be resolved by {@code -Punescape} below.
 *
 * <p>{@code -Punescape=<none|openjdk>} (default {@code none}): a post-processing step applied to
 * the pattern and input fields after reading, before running either engine. {@code openjdk}
 * mirrors {@code RegExTest.grabLine()} exactly (see {@link #unescapeOpenJdk}): needed because
 * OpenJDK's SupplementaryTestCases.txt uses unpaired {@code \backslash-u-XXXX} surrogate escapes that can't
 * survive being decoded in Python (see scrape_openjdk_regex.py's module docstring) -- Java
 * strings, being UTF-16 code unit sequences, have no such restriction.
 *
 * <p>For each tuple, this runs both {@code java.util.regex} and {@code Ll1Pattern} (via {@link
 * MatchRunner}) and writes a full {@link GoldenRow}, with an auto-generated {@code status}:
 *
 * <ul>
 *   <li>{@code AGREES} -- the two engines produced an identical outcome.
 *   <li>{@code UNIMPLEMENTED: ...} -- llk failed to compile or match while regex succeeded, i.e.
 *       looks like a known-missing feature (boundaries, backrefs, lookaround, ambiguity rules).
 *   <li>{@code UNEXPECTED: ...} -- both engines ran to completion but produced different match
 *       outcomes; nothing in the row explains this automatically.
 * </ul>
 *
 * <p><b>These tags are a first-pass heuristic, not a verdict.</b> In particular "UNIMPLEMENTED"
 * really means "llk didn't run cleanly" -- it can just as easily be a *correct* LL(1)-ambiguity
 * rejection (which should be retagged {@code EXPECTED_DIVERGENCE} by hand) as an actually-missing
 * feature. A human pass over every non-{@code AGREES} row is required before trusting the status
 * column; see documents/remaining_work.md.
 *
 * <p>Usage: {@code ./gradlew :llkpattern:generateCorpus -Pinput=<intermediate.tsv>
 * -Poutput=<golden.tsv> -Pmode=MATCHES|LOOKING_AT|FIND}
 */
public final class CorpusGenerator {
  private CorpusGenerator() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 3 && args.length != 4) {
      throw new IllegalArgumentException(
          "Usage: CorpusGenerator <intermediateTsvPath> <goldenTsvOutputPath> <defaultMode> "
              + "[none|openjdk] -- got " + args.length + " args: "
              + java.util.Arrays.toString(args));
    }
    Path input = Paths.get(args[0]);
    Path output = Paths.get(args[1]);
    GoldenRow.Mode defaultMode = GoldenRow.Mode.valueOf(args[2]);
    String unescapeMode = args.length == 4 && !args[3].isEmpty() ? args[3] : "none";

    List<GoldenRow> rows = new ArrayList<>();
    try (BufferedReader r =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(input), StandardCharsets.UTF_8))) {
      String line;
      int lineNum = 0;
      while ((line = r.readLine()) != null) {
        lineNum++;
        if (line.isEmpty()) {
          continue;
        }
        String[] raw = line.split("\t", -1);
        if (raw.length != 3 && raw.length != 4) {
          throw new IllegalArgumentException(
              "Intermediate file " + input + " line " + lineNum + " has " + raw.length
                  + " fields, expected 3 (pattern, flags, input) or 4 (..., mode): " + line);
        }
        String pattern = unescape(unescapeMode, raw[0]);
        String flags = raw[1];
        String inputStr = unescape(unescapeMode, raw[2]);
        GoldenRow.Mode mode = raw.length == 4 ? GoldenRow.Mode.valueOf(raw[3]) : defaultMode;
        rows.add(generateRow(pattern, flags, inputStr, mode));
      }
    }
    GoldenTsv.write(output, rows);

    long agrees = rows.stream().filter(r -> r.status.equals("AGREES")).count();
    long unimplemented =
        rows.stream().filter(r -> r.status.startsWith("UNIMPLEMENTED")).count();
    long unexpected = rows.stream().filter(r -> r.status.startsWith("UNEXPECTED")).count();
    System.out.println(
        "Wrote " + rows.size() + " rows to " + output + ": " + agrees + " AGREES, "
            + unimplemented + " UNIMPLEMENTED (auto-tagged, needs review), " + unexpected
            + " UNEXPECTED (auto-tagged, needs review).");
  }

  private static String unescape(String mode, String field) {
    switch (mode) {
      case "none":
        return field;
      case "openjdk":
        return unescapeOpenJdk(field);
      default:
        throw new IllegalArgumentException(
            "Unknown -Punescape value '" + mode + "' -- did you mean 'none' or 'openjdk'?");
    }
  }

  /** Mirrors OpenJDK's {@code RegExTest.grabLine()} exactly, including its quirks: {@code \n} (a
   *  literal backslash-n) becomes a real newline, then each remaining {@code \backslash-u-XXXX} becomes the
   *  one raw UTF-16 {@code char} with that value -- deliberately NOT combining a high/low
   *  surrogate pair into one code point first, because grabLine() doesn't either (it operates
   *  purely char-unit by char-unit), and SupplementaryTestCases.txt relies on that: some of its
   *  cases emit an intentionally-unpaired surrogate. */
  static String unescapeOpenJdk(String line) {
    int index;
    while ((index = line.indexOf("\\n")) != -1) {
      line = line.substring(0, index) + "\n" + line.substring(index + 2);
    }
    while ((index = line.indexOf("\\u")) != -1) {
      if (index + 6 > line.length()) {
        throw new IllegalArgumentException(
            "Truncated \\backslash-u-XXXX escape at end of '" + line + "' -- expected 4 hex digits after "
                + "\\u.");
      }
      String hex = line.substring(index + 2, index + 6);
      char c = (char) Integer.parseInt(hex, 16);
      line = line.substring(0, index) + c + line.substring(index + 6);
    }
    return line;
  }

  /** Wall-clock budget for one engine attempt (see {@link MatchRunner#runWithTimeout}). Chosen to
   *  be generous for any well-behaved match while staying well under a second even after two
   *  engines x a handful of shrink attempts -- per the project owner (2026-09-06), the point of
   *  this corpus is comparing match *behavior*, not re-benchmarking java.util.regex's
   *  catastrophic-backtracking worst case, so a pathological input gets shrunk (see {@link
   *  #shrinkUntilFast}) rather than waited out. */
  private static final long TIMEOUT_MS = 100;

  static GoldenRow generateRow(String pattern, String flags, String input, GoldenRow.Mode mode) {
    String originalPathologicalInput = "";
    Optional<MatchOutcome> regexOpt = tryRegex(pattern, flags, input, mode);
    Optional<MatchOutcome> llkOpt = tryLlk(pattern, flags, input, mode);
    if (!regexOpt.isPresent() || !llkOpt.isPresent()) {
      originalPathologicalInput = input;
      input = shrinkUntilFast(pattern, flags, input, mode);
      regexOpt = tryRegex(pattern, flags, input, mode);
      llkOpt = tryLlk(pattern, flags, input, mode);
    }
    // If shrinking (down to a 1-character input, see shrinkUntilFast) still doesn't finish within
    // budget, that's not "pathological input" anymore -- it's an engine that hangs on essentially
    // anything for this pattern, which is itself a real, notable divergence worth a golden row
    // rather than a generator crash.
    MatchOutcome regex =
        regexOpt.orElseGet(
            () -> MatchOutcome.matchFailure(new RuntimeException("TimeoutEvenAtMinimalInput")));
    MatchOutcome llk =
        llkOpt.orElseGet(
            () -> MatchOutcome.matchFailure(new RuntimeException("TimeoutEvenAtMinimalInput")));

    String status;
    if (regex.equals(llk)) {
      status = "AGREES";
    } else if (llk.isCompileFailure()) {
      status =
          "UNIMPLEMENTED: llk failed to compile (" + llk.compileException + ") while regex "
              + "succeeded -- auto-tagged, verify by hand (could be a correct LL(1)-ambiguity "
              + "rejection instead; retag EXPECTED_DIVERGENCE if so)";
    } else if (llk.isMatchFailure()) {
      status =
          "UNIMPLEMENTED: llk threw at match time (" + llk.matchException + ") -- auto-tagged, "
              + "verify by hand (likely BoundaryMatcherConstruct/BackReferenceMatcherConstruct, "
              + "see remaining_work.md)";
    } else {
      status =
          "UNEXPECTED: both engines ran to completion but disagree -- regex=" + regex + " llk="
              + llk + " -- needs investigation";
    }
    if (!originalPathologicalInput.isEmpty()) {
      status =
          "PATHOLOGICAL_INPUT_SIMPLIFIED (original " + originalPathologicalInput.length()
              + " chars took >" + TIMEOUT_MS + "ms; see originalPathologicalInput column): "
              + status;
    }
    return new GoldenRow(
        pattern,
        flags,
        input,
        mode,
        regex.compileException,
        regex.matchException,
        regex.encodeResult(),
        llk.compileException,
        llk.matchException,
        llk.encodeResult(),
        status,
        originalPathologicalInput);
  }

  private static Optional<MatchOutcome> tryRegex(
      String pattern, String flags, String input, GoldenRow.Mode mode) {
    GoldenRow probe = new GoldenRow(pattern, flags, input, mode, "", "", "", "", "", "", "", "");
    return MatchRunner.runWithTimeout(() -> MatchRunner.runRegex(probe), TIMEOUT_MS);
  }

  private static Optional<MatchOutcome> tryLlk(
      String pattern, String flags, String input, GoldenRow.Mode mode) {
    GoldenRow probe = new GoldenRow(pattern, flags, input, mode, "", "", "", "", "", "", "", "");
    return MatchRunner.runWithTimeout(() -> MatchRunner.runLlk(probe), TIMEOUT_MS);
  }

  /** Repeatedly halves {@code input}'s length until both engines complete a probe within {@link
   *  #TIMEOUT_MS}, or until it's down to a single character (at which point it's returned as-is
   *  regardless -- {@link #generateRow} handles a still-too-slow minimal input as its own
   *  divergence rather than looping forever). Halving (not e.g. a fixed small length) keeps the
   *  simplified input as close as possible to whatever made the original pathological, since
   *  classic catastrophic-backtracking cases are usually sensitive to input *length* more than to
   *  its exact content. */
  private static String shrinkUntilFast(
      String pattern, String flags, String input, GoldenRow.Mode mode) {
    String candidate = input;
    while (candidate.length() > 1) {
      String shorter = candidate.substring(0, candidate.length() / 2);
      if (tryRegex(pattern, flags, shorter, mode).isPresent()
          && tryLlk(pattern, flags, shorter, mode).isPresent()) {
        return shorter;
      }
      candidate = shorter;
    }
    return candidate;
  }
}
