#!/usr/bin/env python3
"""Scrapes OpenJDK's java.util.regex test-data files into an "intermediate" TSV that
CorpusGenerator consumes.

Source: https://github.com/openjdk/jdk/tree/master/test/jdk/java/util/regex --
BMPTestCases.txt and SupplementaryTestCases.txt. These are consumed by RegExTest.java's
processFile()/grabLine(): each test case is three non-comment lines --
    <pattern>[with an optional 'pattern'flag quoting form]
    <input>
    <expected result -- IGNORED here; CorpusGenerator computes its own via real Matcher runs>

IMPORTANT: this script deliberately does NOT unescape grabLine()'s "\\n"/"\\uXXXX" sequences --
it only splits off the 'pattern'flag quoting form (which never touches those escapes) and passes
the pattern/input text through untouched. The actual unescaping happens in CorpusGenerator
(-Punescape=openjdk), in Java, char-unit by char-unit exactly like grabLine() does. Reason:
SupplementaryTestCases.txt intentionally includes *unpaired* surrogate \\uXXXX escapes (testing
Matcher against invalid-but-legal-as-a-char-sequence UTF-16), and Java strings can represent an
unpaired surrogate char just fine -- but Python str cannot round-trip one through UTF-8 at all
(surrogates aren't valid Unicode scalar values), so doing this decoding here would either crash
or silently corrupt exactly the rows this file exists to cover.

Every case in these two files is exercised via Matcher.find() (see RegExTest.processFile), so
every emitted row's mode is FIND.

Usage:
    python3 scrape_openjdk_regex.py bmp > intermediate_bmp.tsv
    python3 scrape_openjdk_regex.py supplementary > intermediate_supplementary.tsv
Then:
    ./gradlew :llkpattern:generateCorpus -Pinput=intermediate_bmp.tsv \
        -Poutput=llkpattern/src/test/resources/golden/openjdk_bmp.tsv -Pmode=FIND -Punescape=openjdk
"""
import io
import sys
import urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", newline="\n")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8")

SOURCES = {
    "bmp": "https://raw.githubusercontent.com/openjdk/jdk/master/test/jdk/java/util/regex/BMPTestCases.txt",
    "supplementary": "https://raw.githubusercontent.com/openjdk/jdk/master/test/jdk/java/util/regex/SupplementaryTestCases.txt",
}

def split_pattern_and_flags(raw_pattern: str):
    """Mirrors RegExTest.compileTestPattern(): a pattern quoted as 'pattern'i or 'pattern'm."""
    if not raw_pattern.startswith("'"):
        return raw_pattern, ""
    break1 = raw_pattern.rfind("'")
    flag_string = raw_pattern[break1 + 1 :]
    pattern = raw_pattern[1:break1]
    if flag_string == "i":
        return pattern, "CASE_INSENSITIVE"
    if flag_string == "m":
        return pattern, "MULTILINE"
    return pattern, ""


def grab_lines(lines):
    """Yields non-empty, non-comment (//) lines, mirroring grabLine()'s skip loop -- one call
    per grabLine() invocation, i.e. the caller pulls exactly as many as it needs."""
    for line in lines:
        line = line.rstrip("\n").rstrip("\r")
        if line.startswith("//") or len(line) < 1:
            continue
        yield line


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in SOURCES:
        sys.exit(f"Usage: {sys.argv[0]} {{{'|'.join(SOURCES)}}}")
    url = SOURCES[sys.argv[1]]
    with urllib.request.urlopen(url) as resp:
        text = resp.read().decode("utf-8")

    it = grab_lines(text.split("\n"))
    count = 0
    skipped_errors = 0
    for raw_pattern in it:
        try:
            data_line = next(it)
        except StopIteration:
            sys.exit(f"Truncated file: pattern '{raw_pattern}' has no following input/expected lines")
        try:
            expected_line = next(it)
        except StopIteration:
            sys.exit(f"Truncated file: pattern '{raw_pattern}' / input '{data_line}' has no expected-result line")

        # A pattern that's *expected* to fail to compile (PatternSyntaxException) is recorded in
        # the source file as "error" on the expected-result line, per RegExTest.processFile's
        # `if (expectedResult.startsWith("error")) continue;`. We still want that row (llk should
        # also reject it too, or diverge interestingly if it doesn't).
        pattern, flags = split_pattern_and_flags(raw_pattern)
        print("\t".join([pattern, flags, data_line]))
        count += 1
        if expected_line.startswith("error"):
            skipped_errors += 1

    print(
        f"# scraped {count} tuples ({skipped_errors} were JDK-expected-compile-errors, kept anyway) from {url}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
