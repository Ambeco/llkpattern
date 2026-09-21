#!/usr/bin/env python3
"""Scrapes RE2J's (https://github.com/google/re2j) regex test data into an "intermediate" TSV that
CorpusGenerator consumes.

Three sources, all reduced to (pattern, flags, input) tuples -- the expected results the source
files carry are IGNORED (they describe RE2's leftmost-first / POSIX leftmost-longest semantics, not
java.util.regex's); CorpusGenerator computes its own by running both engines:

  find     javatests/com/google/re2j/FindTest.java's FIND_TESTS table: Java string literals
           `new Test("pat", "text", n, ...)`.
  dat      testdata/{basic,nullsubexpr,repetition}.dat: AT&T (Glenn Fowler) regex test files,
           tab-separated `flags  pattern  input  expected`. Only rows whose flags include `E`
           (extended syntax, the closest to Java's) are kept; `NULL` input means "".
  search   testdata/re2-search.txt: blocks of `strings` (Go-quoted) and `regexps` (Go-quoted); every
           regexp in a block is run against every string in that block.

Every row's mode is FIND. Fields are emitted GoldenTsv-escaped (backslash, tab, CR, LF as
backslash-backslash/-t/-r/-n), so use `-Punescape=tsv` with CorpusGenerator. Rows are de-duplicated
on (pattern, flags, input) across all three sources; Go strings that aren't valid UTF-8 are skipped.

Usage:
    python3 scrape_re2j.py > intermediate_re2j.tsv
Then:
    ./gradlew :llkpattern:generateCorpus -Pinput=intermediate_re2j.tsv \
        -Poutput=llkpattern/src/test/resources/golden/re2j.tsv -Pmode=FIND -Punescape=tsv
"""
import io
import re
import sys
import urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", newline="\n")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8")

BASE = "https://raw.githubusercontent.com/google/re2j/master/"
FIND_URL = BASE + "javatests/com/google/re2j/FindTest.java"
DAT_URLS = [BASE + "testdata/" + n for n in ("basic.dat", "nullsubexpr.dat", "repetition.dat")]
SEARCH_URL = BASE + "testdata/re2-search.txt"


def fetch(url):
    with urllib.request.urlopen(url) as resp:
        return resp.read().decode("utf-8")


def tsv_escape(s):
    return (s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n"))


JAVA_SIMPLE = {"n": "\n", "t": "\t", "r": "\r", "f": "\f", "b": "\b", "s": " ", '"': '"', "'": "'", "\\": "\\"}


def unquote_java(lit):
    """Decodes the body of a Java string literal (no surrounding quotes)."""
    out = []
    i = 0
    while i < len(lit):
        c = lit[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        n = lit[i + 1]
        if n in JAVA_SIMPLE:
            out.append(JAVA_SIMPLE[n])
            i += 2
        elif n == "u":
            j = i + 1
            while lit[j] == "u":
                j += 1
            out.append(chr(int(lit[j : j + 4], 16)))
            i = j + 4
        elif n in "01234567":
            j = i + 1
            limit = 3 if n in "0123" else 2
            while j < len(lit) and j - (i + 1) < limit and lit[j] in "01234567":
                j += 1
            out.append(chr(int(lit[i + 1 : j], 8)))
            i = j
        else:
            raise ValueError(f"unknown Java escape \\{n} in {lit!r}")
    return "".join(out)


def unquote_go(lit):
    """Decodes a Go string literal including its quotes; returns None if the result isn't valid
    UTF-8 (Python str can't hold it, and java.util.regex never sees raw bytes anyway)."""
    if lit.startswith("`"):
        return lit[1:-1]
    body = lit[1:-1]
    out = bytearray()
    i = 0
    simple = {"a": 7, "b": 8, "f": 12, "n": 10, "r": 13, "t": 9, "v": 11, "\\": 92, '"': 34, "'": 39}
    while i < len(body):
        c = body[i]
        if c != "\\":
            out += c.encode("utf-8")
            i += 1
            continue
        n = body[i + 1]
        if n in simple:
            out.append(simple[n])
            i += 2
        elif n == "x":
            out.append(int(body[i + 2 : i + 4], 16))
            i += 4
        elif n == "u":
            out += chr(int(body[i + 2 : i + 6], 16)).encode("utf-8", "surrogatepass")
            i += 6
        elif n == "U":
            out += chr(int(body[i + 2 : i + 10], 16)).encode("utf-8", "surrogatepass")
            i += 10
        elif n in "01234567":
            out.append(int(body[i + 1 : i + 4], 8))
            i += 4
        else:
            raise ValueError(f"unknown Go escape \\{n} in {lit!r}")
    try:
        return out.decode("utf-8")
    except UnicodeDecodeError:
        return None


JAVA_STR = r'"((?:[^"\\]|\\.)*)"'
FIND_ROW = re.compile(r"^\s*new Test\(" + JAVA_STR + r",\s*" + JAVA_STR + r",")


def scrape_find():
    for line in fetch(FIND_URL).split("\n"):
        m = FIND_ROW.match(line)
        if m:
            yield unquote_java(m.group(1)), "", unquote_java(m.group(2))


def scrape_dat():
    for url in DAT_URLS:
        flags = ""
        pattern = ""
        for line in fetch(url).split("\n"):
            line = line.rstrip("\r")
            if not line or line.startswith("#") or line.startswith("NOTE"):
                continue
            f = line.split("\t")
            f = [x for i, x in enumerate(f) if x != "" or i == 0]
            if len(f) < 3:
                continue
            if f[0] != "":
                flags = f[0]
            if f[1] != "SAME":
                pattern = f[1]
            text = "" if f[2] == "NULL" else f[2]
            core = re.sub(r":[^:]*:", "", flags)
            if "E" not in core or "$" in core:
                continue
            yield pattern, "CASE_INSENSITIVE" if "i" in core else "", text


GO_STR = r'"(?:[^"\\]|\\.)*"|`[^`]*`'


def scrape_search():
    strings, regexps, mode = [], [], None

    def flush():
        for p in regexps:
            for s in strings:
                yield p, "", s

    for line in fetch(SEARCH_URL).split("\n"):
        line = line.rstrip("\r")
        if line in ("strings", "regexps"):
            if line == "strings" and mode == "regexps":
                yield from flush()
                strings, regexps = [], []
            mode = line
            continue
        if mode is None or not re.fullmatch(GO_STR, line):
            continue
        decoded = unquote_go(line)
        if decoded is not None:
            (strings if mode == "strings" else regexps).append(decoded)
    yield from flush()


def main():
    seen = set()
    counts = {}
    for name, source in (("find", scrape_find), ("dat", scrape_dat), ("search", scrape_search)):
        n = 0
        for pattern, flags, text in source():
            key = (pattern, flags, text)
            if key in seen:
                continue
            seen.add(key)
            print("\t".join([tsv_escape(pattern), flags, tsv_escape(text)]))
            n += 1
        counts[name] = n
    print(f"# scraped {counts} unique tuples from RE2J", file=sys.stderr)


if __name__ == "__main__":
    main()
