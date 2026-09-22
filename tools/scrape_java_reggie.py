#!/usr/bin/env python3
"""Scrapes DataDog/java-reggie's regex integration-test data into an "intermediate" TSV that
CorpusGenerator consumes.

Source: https://github.com/DataDog/java-reggie, reggie-integration-tests/src/main/resources/testsuites/
-- semicolon-delimited `pattern;input;should_match;features` text files (re2-basic.txt,
pcre-patterns.txt) and one JSON file (common/patterns.json, {pattern, testCases: [{input,
shouldMatch}]}). `should_match`/`shouldMatch` is IGNORED, same reasoning as every other scraper
here: CorpusGenerator computes its own outcome from real Matcher runs.

The two capturing-group files (re2-capturing-groups.txt, pcre-capturing-groups.txt) are
deliberately NOT scraped: their pattern column is double-backslash-escaped (e.g. literal text
`\\b(foo)`, not `\b(foo)`) in a way their own parser (RE2CaptureGroupParser/PCRECaptureGroupParser)
never undoes before compiling, so as literal regex source they mean something different from what
the file's own name/intent suggests (an escaped literal backslash then 'b', not a word boundary) --
scraping them as-is would just be testing everyone's handling of accidentally-double-escaped
patterns, not real regex behavior. The plain files checked above only use single-backslash escapes
(one exception, a deliberate Windows-path `\\` in pcre-patterns.txt, is correct as literal regex
source), so they're scraped unescaped.

Usage:
    python3 scrape_java_reggie.py > intermediate_java_reggie.tsv
Then:
    ./gradlew :llkpattern:generateCorpus -Pinput=intermediate_java_reggie.tsv \
        -Poutput=src/test/resources/golden/java_reggie.tsv -Pmode=FIND -Punescape=tsv
"""
import io
import json
import sys
import urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", newline="\n")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8")

BASE = (
    "https://raw.githubusercontent.com/DataDog/java-reggie/main/reggie-integration-tests/"
    "src/main/resources/testsuites/"
)
TEXT_SOURCES = ["re2/re2-basic.txt", "pcre/pcre-patterns.txt"]
JSON_SOURCE = "common/patterns.json"


def fetch(path):
    with urllib.request.urlopen(BASE + path) as resp:
        return resp.read().decode("utf-8")


def scrape_text(path):
    for line in fetch(path).split("\n"):
        line = line.rstrip("\r").strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(";", 3)
        if len(parts) < 2:
            print(f"# skipping malformed line in {path}: {line!r}", file=sys.stderr)
            continue
        yield parts[0], parts[1]


def scrape_json():
    data = json.loads(fetch(JSON_SOURCE))
    for entry in data["patterns"]:
        for case in entry["testCases"]:
            yield entry["pattern"], case["input"]


def tsv_escape(s):
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")


def main():
    seen = set()
    counts = {}
    for path in TEXT_SOURCES + [JSON_SOURCE]:
        n = 0
        rows = scrape_text(path) if path != JSON_SOURCE else scrape_json()
        for pattern, text in rows:
            key = (pattern, text)
            if key in seen:
                continue
            seen.add(key)
            print("\t".join([tsv_escape(pattern), "", tsv_escape(text)]))
            n += 1
        counts[path] = n
    print(f"# scraped {counts} unique tuples from java-reggie", file=sys.stderr)


if __name__ == "__main__":
    main()
