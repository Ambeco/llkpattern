# llkpattern

A regex-like pattern matching library designed to be faster and lower-memory than traditional regex, by compiling patterns using LL(1) parsing techniques instead of backtracking.

## 1. Overview

Traditional regex engines typically rely on backtracking (or build large NFA/DFA structures) to resolve ambiguity between alternative branches, which can cost significant time and memory. llkpattern takes a different approach: at every branch point in a pattern (`+`, `?`, `*`, `|`), it requires that the next input character unambiguously determine which branch to take — the same constraint an LL(1) grammar places on its productions. This lets patterns be compiled directly into an efficient matcher without backtracking, similar in spirit to how an LL(1) parser generator compiles a grammar.

The tradeoff is expressiveness: not every pattern a traditional regex engine accepts can be expressed this way. In exchange, matching can be done in a single deterministic pass, with predictable performance and memory use.

A note on `.` (dot): in V1, within a branching context, `.` matches "all other characters" — i.e., whatever isn't already claimed by a sibling branch — rather than "any character," to preserve unambiguous branch selection. Broader support for unconditionally selecting a first matching branch (e.g., using Unicode categories) may be added in a future version, but is out of scope for V1.

The public API is intended to be a near drop-in replacement for `java.util.regex.Pattern`/`Matcher`, so existing regex-based code can adopt it with minimal changes (`Matcher` mirrors `java.util.regex.Matcher`'s method surface, and `PatternParser`'s grammar is documented as a regex-flavored BNF).

## 2. High-Level Design

_(Summary — see [documents/design.md](documents/design.md) for the full design)_

**Pipeline:** a pattern string is parsed (`PatternParser`, recursive descent, grammar documented as regex-flavored BNF) into a `PatternConstruct` AST (unions, sequences, literals, character classes, quantifiers, boundaries, backreferences, groups), then compiled into a `MatcherConstruct` graph that a `Matcher` walks one code point at a time, with no backtracking.

**Compile-time ambiguity checking:** each construct computes its own "entry point" — the set of code points that could start it — independently of building its matcher. Merging two candidate branches (a union's `|` arms, or a loop's body-vs-exit routing) checks their entry points for overlap and rejects the pattern at compile time if two branches could both match the same next character; this is the actual LL(1) constraint being enforced. Entry-point maps are aliased between constructs wherever one construct's entry point is exactly another's, rather than copied, to keep compilation allocation-light.

**Opcode-style matcher graph:** the compiled graph is a small set of node "opcodes" — single-successor nodes (character/literal match, capture begin/end, boundaries) called via a plain virtual `match()`, and multi-way dispatch nodes (union branches, loop entry/re-entry) that route on the next code point via a `CodePointMap`-backed dispatch table. A loop compiles to the same dispatch-node shape as a union, just wired to loop back to itself.

**Unicode code point ranges (`CodePointMap`):** a `RangeMap`-style interface over the Unicode code point domain (`[0, 0x10FFFF]`), backed by `ArrayCodePointMap` — two flat, sorted parallel arrays instead of a tree of range objects, chosen for cache-friendliness and low per-entry overhead on the sets this library builds most (`TreeCodePointMap`, a Guava `TreeRangeMap` adapter, exists only as its differential-test oracle). Named classes (`\p{Alpha}`, Unicode categories, POSIX classes, `\d`/`\w`/`\s`, etc., in `NamedCharClass`/`UnicodePredicates`) and the parser's own character-class handling both build on this.

**Boundary matching** (`^`/`$`/`\A`/`\Z`/`\z`/`\b`/`\B`) is resolved per-construct against `MULTILINE`/`UNIX_LINES`, with a compile-time optimization that classifies the character immediately before/after a `\b`/`\B` as statically word/non-word when possible, skipping a runtime check.

The public API mirrors `java.util.regex.Pattern`/`Matcher` closely enough to be a near drop-in replacement for existing regex-based code, modulo the LL(1) expressiveness tradeoff described above.

## 3. Current Progress

The module compiles; its test suite passes fully (1484 tests, 0 failing, 561 skipped — see [documents/remaining_work.md](documents/remaining_work.md) for the JDK version this currently requires and the active TODO list). A scraped-corpus differential test harness (compares `Ll1Pattern` against `java.util.regex` on real test data mined from OpenJDK's own regex test suite) has already surfaced and helped fix several real bugs — see [documents/notes.md](documents/notes.md) for that history and remaining_work.md's "Scraped-corpus differential test harness" section for current corpus-agreement counts (most of the corpus's remaining `UNIMPLEMENTED`/`UNEXPECTED` rows are still-unimplemented features like lookaround/Unicode scripts, not yet human-triaged).

In brief:

- **Parsing** (`PatternParser`, `PatternConstruct`): a recursive-descent parser turns a pattern string into an AST of `PatternConstruct` nodes (unions, sequences, literals, character classes, quantifiers, boundaries, backreferences, groups). This layer is fairly mature; several real parsing bugs were found and fixed while building out the compiler (see [documents/notes.md](documents/notes.md)).
- **Code point range representation** (`CodePointMap`/`ArrayCodePointMap`): done and tested. A `RangeMap`-style interface over Unicode code points, backed by a specialized two-flat-array implementation (`TreeCodePointMap`, a Guava `TreeRangeMap` adapter, is kept only as its differential-test oracle).
- **Compilation** (`PatternConstruct` → `MatcherConstruct`): working for literals, character classes, sequences, alternation (with real ambiguity detection — two `|` branches that could match the same next character are a compile-time error), quantifiers/loops (`?`, `*`, `+`, `{n,m}`), and capturing groups — including a group that's both capturing and quantified at once (e.g. `(a)*`), which correctly captures whichever iteration matched last, per real regex semantics. Boundary matching is implemented for `\b`/`\B` (with a compile-time optimization for the common case where a boundary sits next to a statically-word/non-word literal or character class), `^`/`$`/`\A`/`\Z`/`\z` (honoring `MULTILINE`/`UNIX_LINES`). Backreferences (`\1`-`\9`, `\k<name>`) are implemented, using a precise compile-time entry set computed from the referenced group so ordinary usage stays fully LL(1)-checked (see [documents/design.md](documents/design.md)'s "Backreferences" section). `\G` doesn't match a position at all — see design.md's "Boundary matching" section.
- **Matching** (`Matcher`): core API implemented and tested — `matches()`, `lookingAt()`, `find()`/`find(int)`, numbered and named group accessors, regions, `reset()`. Replacement (`replaceAll`/`replaceFirst`/etc.), `split`, and a few other corners are still stubs.
- **Supporting pieces**: `NamedCharClass`/`UnicodePredicates` (Unicode category/script/block support) and the `unicodeanalyzer` module (its code generator) are largely built out.
- `oldllkpattern/` holds an earlier version of the implementation, kept for reference during the ongoing refactor.

## 4. Authorship

The original base implementation was written by [github.com/Ambeco](https://github.com/Ambeco). Claude (Anthropic) has taken over the implementation from that base, working under Ambeco's guidance and direction.
