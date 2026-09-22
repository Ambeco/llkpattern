#!/usr/bin/env python3
"""Scrapes dregex's (https://github.com/marianobarrios/dregex) own regex test data into an
"intermediate" TSV that CorpusGenerator consumes.

Source: src/test/java/dregex/MatchTest.java -- test cases are Java source, not a data file: each is
a `{ var r = Regex.compile("pattern"); assertTrue(r.matchesAtLeastOne()); assertTrue/False(r.matches
("input")); ... }` block, one `Regex.compile` per block, any number of `r.matches(...)` calls after
it (each a separate (pattern, input) tuple; `assertTrue`/`assertFalse` themselves are ignored, same
reasoning as every other scraper here -- CorpusGenerator computes its own outcome). Mode is MATCHES
(whole-string), matching what `r.matches` tests. `r.matchesAtLeastOne()` calls (no input) are
skipped, as are the handful of blocks with no `r.matches(...)` call at all.

Java string literal escapes (`\\n`, `\\\\`, `\\"`, `\\uXXXX`) are decoded.

Usage:
    python3 scrape_dregex.py > intermediate_dregex.tsv
Then:
    ./gradlew :llkpattern:generateCorpus -Pinput=intermediate_dregex.tsv \
        -Poutput=src/test/resources/golden/dregex.tsv -Pmode=MATCHES -Punescape=tsv
"""
import io
import re
import sys
import urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", newline="\n")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8")

URL = "https://raw.githubusercontent.com/marianobarrios/dregex/master/src/test/java/dregex/MatchTest.java"

JAVA_STR = r'"((?:[^"\\]|\\.)*)"'
COMPILE = re.compile(r"Regex\.compile\(" + JAVA_STR + r"\)")
MATCHES = re.compile(r"\br\.matches\(" + JAVA_STR + r"\)")

JAVA_SIMPLE = {"n": "\n", "t": "\t", "r": "\r", "f": "\f", "b": "\b", '"': '"', "'": "'", "\\": "\\"}


def unquote_java(lit):
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
        else:
            raise ValueError(f"unknown Java escape \\{n} in {lit!r}")
    return "".join(out)


def tsv_escape(s):
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")


def main():
    with urllib.request.urlopen(URL) as resp:
        text = resp.read().decode("utf-8")

    # Split the whole file on each `Regex.compile(...)` call; text between one match and the next
    # is that pattern's own block (its assertTrue/assertFalse(r.matches(...)) calls), except for
    # the file's very last chunk, which trails off into the next @Test method.
    pieces = list(COMPILE.finditer(text))
    n = 0
    for i, m in enumerate(pieces):
        pattern = unquote_java(m.group(1))
        block_end = pieces[i + 1].start() if i + 1 < len(pieces) else len(text)
        block = text[m.end() : block_end]
        for m2 in MATCHES.finditer(block):
            input_str = unquote_java(m2.group(1))
            print("\t".join([tsv_escape(pattern), "", tsv_escape(input_str)]))
            n += 1
    print(f"# scraped {n} tuples from {len(pieces)} dregex patterns", file=sys.stderr)


if __name__ == "__main__":
    main()
