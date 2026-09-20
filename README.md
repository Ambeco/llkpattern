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

### Sample usage

```java
// Compiling and matching looks exactly like java.util.regex:
Ll1Pattern pattern = Ll1Pattern.compile("(\\d{3})-(\\d{4})");
Matcher matcher = pattern.matcher("Call 555-1234 now");
if (matcher.find()) {
  System.out.println(matcher.group());   // "555-1234"
  System.out.println(matcher.group(1));  // "555"
}

// A drop-in replacement for existing regex-based code is usually just this:
// - java.util.regex.Pattern.compile(...)  ->  Ll1Pattern.compile(...)
// - java.util.regex.Matcher              ->  com.tbohne.llkpattern.Matcher
// (`Matcher`'s method surface mirrors java.util.regex.Matcher's directly.)

// Two branches that could both match the same next character are a compile-time
// error, not a silent ambiguity -- this is the LL(1) constraint the whole engine
// is built around:
Ll1Pattern.compile("a|ab"); // throws PatternSyntaxException: both branches start with 'a'
```

### Intentional differences from `java.util.regex`

These are deliberate, and each is checked against `java.util.regex` by the scraped-corpus tests (tagged
`EXPECTED_DIVERGENCE`). Everything else that differs is a gap to be fixed, not a design choice; see
[documents/remaining_work.md](documents/remaining_work.md).

- **Ambiguity is a compile-time error.** If two `|` branches, or a loop's body and whatever follows it, could both
  start with the same character, `Ll1Pattern.compile` throws `PatternSyntaxException` instead of backtracking.
  `a|ab`, `(aaa)?aaa`, `.+b` and `a(b){4,5}b` are rejected; `a(b){4,5}c` and `a|b` are fine. Under
  `CASE_INSENSITIVE`, branches are compared after case folding, so `(?i:a|A)` and `(?i:[a-z]+)X` are rejected too.
- **Lookahead and lookbehind are rejected** (`(?=...)`, `(?!...)`, `(?<=...)`, `(?<!...)`), since they cannot be
  guaranteed to run in linear time.
- **Reluctant and possessive quantifier modifiers are accepted but are no-ops**, since there is no backtracking to be
  reluctant about. `a{2,3}?` matches `aaa` here, where `java.util.regex` matches `aa`.

Not yet implemented (these will be supported eventually): the `LITERAL` and `CANON_EQ` flags,
and the `Matcher` replace/split methods.

### Considered and deliberately not added

- **A client-runnable Unicode generator + external data file.** Letting a client run the Unicode generator themselves and having the library prefer that data file over its built-in ranges. Rejected: substantial work and likely slower compile/match, for very little value, since the built-in data is regenerated from a newer JDK when needed. See [documents/design.md](documents/design.md)'s "Alternatives Considered".

## 3. Remaining Work

See [documents/remaining_work.md](documents/remaining_work.md) for the full, actively-maintained list. Some of the more interesting open items:

- **Lookahead/lookbehind** — rejected outright at parse time, since they can't be guaranteed to run in linear time.
- **`LITERAL`/`CANON_EQ` compile flags** — unimplemented from scratch; `UNIX_LINES` is only partially honored (affects `^`/`$`/`\Z` but not yet `.`/`\s`/etc.'s line-terminator handling).
- **`Matcher`/`Ll1Pattern` API gaps** — `replaceAll`/`replaceFirst`/`split` and friends, `region()`'s interaction with anchoring/transparent bounds, are still stubs.
- **More scraped-corpus sources planned** beyond OpenJDK — AOSP/libcore, RE2J (another non-backtracking engine, interesting as a design comparison), dregex, dk.brics.automaton, and DataDog/java-reggie are all identified candidates.
- **`ArrayCodePointMap` density experiment**: a proposed bitmask-entry variant (trading lookup speed for density on alternating-but-non-contiguous data, e.g. `isLowerCase`) hasn't been tried yet.

## 4. Current Progress

The module compiles; its test suite passes fully: **1487 tests, 0 failing** — 365 hand-written unit/integration tests, plus **561 tests from a scraped-corpus differential harness** (compares `Ll1Pattern` against real test data mined from OpenJDK's own `java.util.regex` test suite; more corpus sources are planned, see above) and 561 further reference-only checks (re-verifying `java.util.regex`'s own recorded behavior against the installed JDK) that are disabled by default, hence "skipped" rather than run. See [documents/remaining_work.md](documents/remaining_work.md) for the JDK version required to run the suite and the full TODO list, and [documents/notes.md](documents/notes.md) for the bugs this harness has already found and fixed.

In brief:

- **Parsing** (`PatternParser`, `PatternConstruct`): a recursive-descent parser turns a pattern string into an AST of `PatternConstruct` nodes (unions, sequences, literals, character classes, quantifiers, boundaries, backreferences, groups). This layer is fairly mature; several real parsing bugs were found and fixed while building out the compiler (see [documents/notes.md](documents/notes.md)).
- **Code point range representation** (`CodePointMap`/`ArrayCodePointMap`): done and tested. A `RangeMap`-style interface over Unicode code points, backed by a specialized two-flat-array implementation (`TreeCodePointMap`, a Guava `TreeRangeMap` adapter, is kept only as its differential-test oracle).
- **Compilation** (`PatternConstruct` → `MatcherConstruct`): working for literals, character classes, sequences, alternation (with real ambiguity detection — two `|` branches that could match the same next character are a compile-time error), quantifiers/loops (`?`, `*`, `+`, `{n,m}`), and capturing groups — including a group that's both capturing and quantified at once (e.g. `(a)*`), which correctly captures whichever iteration matched last, per real regex semantics. Boundary matching is implemented for `\b`/`\B` (with a compile-time optimization for the common case where a boundary sits next to a statically-word/non-word literal or character class), `^`/`$`/`\A`/`\Z`/`\z` (honoring `MULTILINE`/`UNIX_LINES`). Backreferences (`\1`-`\9`, `\k<name>`) are implemented, using a precise compile-time entry set computed from the referenced group so ordinary usage stays fully LL(1)-checked (see [documents/design.md](documents/design.md)'s "Backreferences" section). `\G` doesn't match a position at all — see design.md's "Boundary matching" section.
- **Matching** (`Matcher`): core API implemented and tested — `matches()`, `lookingAt()`, `find()`/`find(int)`, numbered and named group accessors, regions, `reset()`. Replacement (`replaceAll`/`replaceFirst`/etc.), `split`, and a few other corners are still stubs.
- **Supporting pieces**: `NamedCharClass`/`UnicodePredicates` (Unicode category/script/block support) and the `unicodeanalyzer` module (its code generator) are largely built out.
- `oldllkpattern/` holds an earlier version of the implementation, kept for reference during the ongoing refactor.

### Benchmarks

Both tables are milliseconds per pass over the full OpenJDK-derived test corpus (lower is better), measured via JMH on desktop (`CorpusBenchmark`, [benchmarks/Intel-i7-9750H_corpus_benchmark_results.json](benchmarks/Intel-i7-9750H_corpus_benchmark_results.json)) and an instrumented on-device benchmark on Android (`AndroidCorpusBenchmark`, [benchmarks/Google_Pixel_3a_sargo_corpus_benchmark_results.json](benchmarks/Google_Pixel_3a_sargo_corpus_benchmark_results.json)). The two harnesses don't use identical corpus subsets, so treat cross-device comparisons as approximate — see remaining_work.md's benchmark sections for the full caveats.

Both tables were re-measured 2026-09-19 on a corpus that had grown from 406 to ~480 rows since the
previous numbers (more rows triaged to `AGREES`, including large Unicode-class and bracket-intersection
patterns that llk compiles slowly), so they are not comparable to the older figures below.

Absolute ms/pass varies run to run with background load on either device (see notes.md); the
llkpattern/regex **ratio** column (last, in each table below) is the more stable number to track
over time.

**Corpus compile time (each pass compiles ~480 patterns):**

| | regex (ms/pass) | llkpattern (ms/pass) | llk/regex ratio |
|---|---|---|---|
| Intel-i7-9750H | 0.126 | 0.463 | 3.67x |
| Pixel 3a | 9.87 | 10.12 | 1.03x |

**Corpus match time (each pass matches/finds/look_ats ~480 patterns):**

| | regex (ms/pass) | llkpattern (ms/pass) | llk/regex ratio |
|---|---|---|---|
| Intel-i7-9750H | 0.055 | 0.045 | 0.82x |
| Pixel 3a | 4.97 | 0.89 | 0.18x |

llkpattern still compiles slower than `java.util.regex` on desktop (compilation does real ambiguity-detection work `java.util.regex` skips), though the gap has narrowed substantially after this project's move to fork-chain dispatch, and further after later sessions removed the map-based ambiguity-check allocation (`entryMap`'s `CodePointMap` -> `CodePointSet` migration, then `checkDisjoint`'s allocation-free overlap check) — see notes.md for the compile-time performance history. On the Pixel 3a the two compile times currently land close to each other and vary run-to-run (see notes.md), so don't read that ratio as settled. Match time is faster than `java.util.regex` on both devices, notably so on the Pixel 3a, though that device comparison isn't yet fully understood (see "Remaining Work" above).

**flatten-matcher-dispatch experiment branch (2026-09-18):** both rows above are from this
experiment (Intel re-measured on a quiet desktop once a concurrent session and browser tabs were
closed; Pixel 3a from the same session as the code change). Desktop compile time improved slightly
(0.243 -> 0.235ms/pass, ~3% faster) and desktop match time moved from 0.034 to 0.036ms/pass (~6%
slower, within this measurement's own ~11% error bar, so not clearly a real regression) —
allocation sampling (not timing-sensitive) showed match-time allocation completely unchanged in
shape, still dominated by `Matcher.<init>`, consistent with "no real change" for match time. Pixel
3a showed a real if modest speedup for both (compile 5.76->5.55ms/pass, match 0.77->0.76ms/pass).
Net read: a small, real compile-time win, a wash on match time, achieved with fewer allocated node
objects per union/loop -- see notes.md for the full numbers and remaining_work.md for what's still
open (design.md's "Quantifier/loop compilation" section rewrite) before this merges to main.

## 5. Authorship

The original base implementation was written by [github.com/Ambeco](https://github.com/Ambeco). Claude (Anthropic) has taken over the implementation from that base, working under Ambeco's guidance and direction.
