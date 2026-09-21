#!/usr/bin/env python3
"""Scrapes AOSP libcore's copy of the java.util.regex test-data files into an "intermediate" TSV
that CorpusGenerator consumes (with -Punescape=openjdk, exactly like scrape_openjdk_regex.py).

Source: https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/test/java/util/regex/
-- BMPTestCases.txt, SupplementaryTestCases.txt and TestCases.txt (the original ASCII set that
OpenJDK's BMP/Supplementary files were derived from; not scraped from OpenJDK itself).
GraphemeTestCases.txt is skipped: it tests \\X and \\b{g}, which are not implemented.

Same file format and pattern/flags handling as scrape_openjdk_regex.py (which this imports). Rows
that scrape_openjdk_regex.py already yields for OpenJDK's BMP and supplementary files are dropped,
as are duplicates within this scrape, so the golden file only holds new tuples.

Usage:
    python3 scrape_aosp_regex.py > intermediate_aosp.tsv
Then:
    ./gradlew :llkpattern:generateCorpus -Pinput=intermediate_aosp.tsv \
        -Poutput=src/test/resources/golden/aosp.tsv -Pmode=FIND -Punescape=openjdk
"""
import base64
import sys
import urllib.request

import scrape_openjdk_regex as jdk

BASE = "https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/test/java/util/regex/"
FILES = ["BMPTestCases.txt", "SupplementaryTestCases.txt", "TestCases.txt"]


def fetch_aosp(name):
    with urllib.request.urlopen(BASE + name + "?format=TEXT") as resp:
        return base64.b64decode(resp.read()).decode("utf-8")


def rows(text):
    it = jdk.grab_lines(text.split("\n"))
    for raw_pattern in it:
        data_line = next(it)
        next(it)  # expected result: ignored, see scrape_openjdk_regex.py
        pattern, flags = jdk.split_pattern_and_flags(raw_pattern)
        yield pattern, flags, data_line


def main():
    seen = set()
    for url in jdk.SOURCES.values():
        with urllib.request.urlopen(url) as resp:
            seen.update(rows(resp.read().decode("utf-8")))
    counts = {}
    for name in FILES:
        n = 0
        for row in rows(fetch_aosp(name)):
            if row in seen:
                continue
            seen.add(row)
            print("\t".join(row))
            n += 1
        counts[name] = n
    print(f"# scraped new tuples per file: {counts}", file=sys.stderr)


if __name__ == "__main__":
    main()
