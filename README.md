# llpattern

A regex-like pattern matching library designed to be faster and lower-memory than traditional regex, by compiling patterns using LL(1) parsing techniques instead of backtracking.

## 1. Overview

Traditional regex engines typically rely on backtracking (or build large NFA/DFA structures) to resolve ambiguity between alternative branches, which can cost significant time and memory. llpattern takes a different approach: at every branch point in a pattern (`+`, `?`, `*`, `|`), it requires that the next input character unambiguously determine which branch to take — the same constraint an LL(1) grammar places on its productions. This lets patterns be compiled directly into an efficient matcher without backtracking, similar in spirit to how an LL(1) parser generator compiles a grammar.

The tradeoff is expressiveness: not every pattern a traditional regex engine accepts can be expressed this way. In exchange, matching can be done in a single deterministic pass, with predictable performance and memory use.

A note on `.` (dot): in V1, within a branching context, `.` matches "all other characters" — i.e., whatever isn't already claimed by a sibling branch — rather than "any character," to preserve unambiguous branch selection. Broader support for unconditionally selecting a first matching branch (e.g., using Unicode categories) may be added in a future version, but is out of scope for V1.

The public API is intended to be a near drop-in replacement for `java.util.regex.Pattern`/`Matcher`, so existing regex-based code can adopt it with minimal changes (`Matcher` mirrors `java.util.regex.Matcher`'s method surface, and `PatternParser`'s grammar is documented as a regex-flavored BNF).

## 2. High-Level Design

_(TBD — see [documents/design.md](documents/design.md))_

## 3. Current Progress

The module compiles; its test suite passes fully (1439 tests, 0 failing, 561 skipped — see [documents/remaining_work.md](documents/remaining_work.md) for the JDK version this currently requires and the active TODO list). A scraped-corpus differential test harness (compares `Ll1Pattern` against `java.util.regex` on real test data mined from OpenJDK's own regex test suite) has already surfaced and helped fix several real bugs — see [documents/notes.md](documents/notes.md) for that history and remaining_work.md's "Scraped-corpus differential test harness" section for current corpus-agreement counts (most of the corpus's remaining `UNIMPLEMENTED`/`UNEXPECTED` rows are still-unimplemented features like lookaround/Unicode scripts, not yet human-triaged).

In brief:

- **Parsing** (`PatternParser`, `PatternConstruct`): a recursive-descent parser turns a pattern string into an AST of `PatternConstruct` nodes (unions, sequences, literals, character classes, quantifiers, boundaries, backreferences, groups). This layer is fairly mature; several real parsing bugs were found and fixed while building out the compiler (see [documents/notes.md](documents/notes.md)).
- **Code point range representation** (`CodePointMap`/`TreeCodePointMap`): done and tested. A `RangeMap`-style interface over Unicode code points, currently backed by Guava's `TreeRangeMap`, with the intent to swap in something more specialized later without touching callers.
- **Compilation** (`PatternConstruct` → `MatcherConstruct`): working for literals, character classes, sequences, alternation (with real ambiguity detection — two `|` branches that could match the same next character are a compile-time error), quantifiers/loops (`?`, `*`, `+`, `{n,m}`), and capturing groups — including a group that's both capturing and quantified at once (e.g. `(a)*`), which correctly captures whichever iteration matched last, per real regex semantics. Boundary matching is implemented for `\b`/`\B` (with a compile-time optimization for the common case where a boundary sits next to a statically-word/non-word literal or character class), `^`/`$`/`\A`/`\Z`/`\z` (honoring `MULTILINE`/`UNIX_LINES`). Backreferences (`\1`-`\9`, `\k<name>`) are implemented, using a precise compile-time entry set computed from the referenced group so ordinary usage stays fully LL(1)-checked (see [documents/design.md](documents/design.md)'s "Backreferences" section). `\G` doesn't match a position at all — see design.md's "Boundary matching" section.
- **Matching** (`Matcher`): core API implemented and tested — `matches()`, `lookingAt()`, `find()`/`find(int)`, numbered and named group accessors, regions, `reset()`. Replacement (`replaceAll`/`replaceFirst`/etc.), `split`, and a few other corners are still stubs.
- **Supporting pieces**: `NamedCharClass`/`UnicodePredicates` (Unicode category/script/block support) and the `unicodeanalyzer` module (its code generator) are largely built out.
- `oldllkpattern/` holds an earlier version of the implementation, kept for reference during the ongoing refactor.

## 4. Authorship

The original base implementation was written by [github.com/Ambeco](https://github.com/Ambeco). Claude (Anthropic) has taken over the implementation from that base, working under Ambeco's guidance and direction.
