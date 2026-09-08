package com.tbohne.llkpattern.corpus;

/**
 * On-device counterpart of {@code llkpattern/src/test/java/.../corpus/GoldenRow.java}, trimmed to
 * just the fields {@link AndroidCorpusBenchmark} needs.
 *
 * <p>This is a separate, read-only reimplementation rather than a dependency on the desktop
 * {@code GoldenRow}/{@code GoldenTsv} classes, because those live in {@code llkpattern}'s {@code
 * test} source set (not exported as a normal Gradle project dependency) and {@code GoldenTsv}'s
 * file reading goes through {@code java.nio.file.Path}, which needs API 26+ / core library
 * desugaring that this app module doesn't otherwise need. See documents/notes.md's
 * on-device-benchmark entry.
 *
 * <p>Column order and escaping (backslash, tab, CR, LF as {@code \\}, {@code \t}, {@code \r},
 * {@code \n}; a lone UTF-16 surrogate as {@code \xHHHH}) must stay in sync with {@code
 * GoldenTsv}/{@code GoldenRow} -- see those classes' javadoc for the full column list.
 */
final class AndroidGoldenRow {
  enum Mode {
    MATCHES,
    LOOKING_AT,
    FIND
  }

  final String pattern;
  final String flags;
  final String input;
  final Mode mode;
  final String regexCompileException;
  final String llkCompileException;
  final String status;

  AndroidGoldenRow(
      String pattern,
      String flags,
      String input,
      Mode mode,
      String regexCompileException,
      String llkCompileException,
      String status) {
    this.pattern = pattern;
    this.flags = flags;
    this.input = input;
    this.mode = mode;
    this.regexCompileException = regexCompileException;
    this.llkCompileException = llkCompileException;
    this.status = status;
  }

  int flagBits() {
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
            "Unknown regex flag name '" + name + "' in a golden TSV row -- did you mean to add "
                + "it to AndroidGoldenRow.flagBit()? Known names: CANON_EQ, CASE_INSENSITIVE, "
                + "COMMENTS, DOTALL, LITERAL, MULTILINE, UNICODE_CASE, "
                + "UNICODE_CHARACTER_CLASS, UNIX_LINES.");
    }
  }
}
