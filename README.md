# llkpattern

A regex-like pattern matching library that exists to trade away backtracking, lookahead, and lookbehind — regex features that are real but uncommonly used — for significantly faster, lower-memory matching, particularly on Android, by compiling patterns using LL(1) parsing techniques instead.

## 1. Overview

Traditional regex engines typically rely on backtracking (or build large NFA/DFA structures) to resolve ambiguity between alternative branches, which can cost significant time and memory. llkpattern takes a different approach: at every branch point in a pattern (`+`, `?`, `*`, `|`), it requires that the next input character unambiguously determine which branch to take — the same constraint an LL(1) grammar places on its productions. This lets patterns be compiled directly into an efficient matcher without backtracking, similar in spirit to how an LL(1) parser generator compiles a grammar.

The tradeoff is expressiveness: not every pattern a traditional regex engine accepts can be expressed this way. In exchange, matching can be done in a single deterministic pass, with predictable performance and memory use.

A note on `.` (dot): in V1, within a branching context, `.` matches "all other characters" — i.e., whatever isn't already claimed by a sibling branch — rather than "any character," to preserve unambiguous branch selection. Broader support for unconditionally selecting a first matching branch (e.g., using Unicode categories) may be added in a future version, but is out of scope for V1.

The public API is intended to be a near drop-in replacement for `java.util.regex.Pattern`/`Matcher`, so existing regex-based code can adopt it with minimal changes (`Matcher` mirrors `java.util.regex.Matcher`'s method surface, and `PatternParser`'s grammar is documented as a regex-flavored BNF).

### Intentional differences from `java.util.regex`

These are deliberate, and each is checked against `java.util.regex` by the scraped-corpus tests (tagged
`EXPECTED_DIVERGENCE`). Everything else that differs is a gap to be fixed, not a design choice; see
[documents/remaining_work.md](documents/remaining_work.md).

- **Ambiguity is a compile-time error.** If two `|` branches, or a loop's body and whatever follows it, could both
  start with the same character, `Ll1Pattern.compile` throws `PatternSyntaxException` instead of backtracking.
  `a|ab`, `(aaa)?aaa`, `.+b` and `a(b){4,5}b` are rejected; `a(b){4,5}c` and `a|b` are fine. Under
  `CASE_INSENSITIVE`, branches are compared after case folding, so `(?i:a|A)` and `(?i:[a-z]+)X` are rejected too.
  This one constraint (a single, committed position with only a one-code-point look ahead/behind — see design.md)
  is also the underlying reason for the next two entries:
  - **Lookahead, and lookbehind of more than one code point, are rejected — a side effect of the same
    constraint.** Neither is implemented yet; both currently throw `PatternSyntaxException` at parse time.
    General lookahead, and lookbehind longer than one code point, will continue to be rejected permanently:
    matching here is driven entirely by a single, committed position with only a one-code-point look
    ahead/behind, so anything requiring a longer look before committing to a branch is out of scope by design,
    not merely unimplemented. A lookbehind of exactly one code point (`(?<=x)`/`(?<!x)`) is planned, as a direct
    generalization of the one-code-point-back check `\b`/`\B` already do; lookahead has no equivalent carve-out
    and stays permanently rejected in every form.
  - **A multi-character loop body that matches part of itself, then fails, is not retried with fewer iterations —
    a side effect of the same constraint.** E.g. `(ab)+` finds nothing in `"abac"` where `java.util.regex` finds
    `"ab"`, since there's no way to un-consume the `a` already read while checking the failed second iteration.
    This also covers a backreference to a multi-code-point group used in a loop (`(ab)\1?` doesn't match `"aba"`);
    a backreference to a single-code-point group (including a multi-valued one, e.g. `([ab])\1?`) isn't affected,
    since a mismatch there is always caught before anything is consumed.
- **A backreference to a group number with no group of that number open yet — forward references (`\1(a)`) and
  references to a group that never exists at all (`\141`, i.e. `\1` plus literal `"41"`) alike — is a compile-time
  error**, rather than the structurally-dead-on-arrival node `java.util.regex` compiles (one that can never match
  any input, since it always finds the referenced group unset).
- **A pattern that can never match any input is a compile-time error**, rather than something `java.util.regex`
  compiles successfully and then simply never matches — e.g. a nullable loop body (`(a*)*`), `a\bb` (no word
  boundary can ever sit between two word characters), or the nonexistent-group backreferences above. Consistent
  with the rest of this list: an unsatisfiable pattern is treated as a mistake to report, not a silent no-op.
- **Unicode data is currently pinned to JDK 27's tables** (Unicode Character Database version 17.0, Unicode
  Consortium CLDR version 48.2; see [documents/design.md](documents/design.md)'s "Unicode support" section for how
  to regenerate them from a different JDK). Named classes (`\p{...}`, scripts, blocks, categories) and case
  folding may disagree with `java.util.regex` when running on a JRE whose own Unicode version is older or newer
  than that. `\N{name}` is the one exception: it looks the name up against the *running platform's* own
  `Character.codePointOf`, not JDK 27's baked-in tables (see below).

- **`CANON_EQ` is a pattern rewrite**: each base-plus-combining-marks cluster (or precomposed character) in the
  pattern becomes a group of every canonically equivalent spelling, left-factored so its branches stay unambiguous;
  the input is never normalized. Differences from `java.util.regex`: a loop over a cluster (`\u00e9+`) doesn't retry
  fewer iterations (the general no-backtracking limit); classes, `.` and `\w` never swallow trailing combining marks
  (the JDK is inconsistent: `[^x]` and `\p{L}` do, `[a-z]` and `\w` don't); a partly composed Hangul syllable
  (U+AC00 U+11A8 for U+AC01) matches here and not in the JDK; `\Q...\E` text is rewritten too; a cluster inside a
  negated, nested or range-bounding class is a compile error; error positions refer to the rewritten text.
  `LITERAL` overrides `CANON_EQ`, as in the JDK.

Except for the divergences listed above, llkpattern matches the same results as JDK 27's `java.util.regex`.

## 2. High-Level Design

_(Summary — see [documents/design.md](documents/design.md) for the full design)_

**Pipeline:** a pattern string is parsed (`PatternParser`, recursive descent, grammar documented as regex-flavored BNF) into a `PatternConstruct` AST (unions, sequences, literals, character classes, quantifiers, boundaries, backreferences, groups), then compiled into a deeply-immutable `MatcherConstruct` graph that a `Matcher` walks one code point at a time, with no backtracking.

**Compile-time ambiguity checking:** each construct computes its own "entry point" — the set of code points that could start it — independently of building its matcher. Merging two candidate branches (a union's `|` arms, or a loop's body-vs-exit routing) checks their entry points for overlap and rejects the pattern at compile time if two branches could both match the same next character; this is the actual LL(1) constraint being enforced. Entry-point maps are aliased between constructs wherever one construct's entry point is exactly another's, rather than copied, to keep compilation allocation-light.

**Opcode-style matcher graph:** the compiled graph is a small set of node "opcodes" — single-successor nodes (character/literal match, capture begin/end, boundaries) called via a plain virtual `match()`, and multi-way dispatch nodes (union branches, loop entry/re-entry) that route on the next code point via a `CodePointSet`-backed dispatch table. A loop compiles to the same dispatch-node shape as a union, just wired to loop back to itself.

**Unicode code point ranges (`CodePointSet`):** a `RangeSet`-style pure-membership interface over the Unicode code point domain (`[0, 0x10FFFF]`), backed by `ArrayCodePointSet` — a single flat, sorted array of packed ranges instead of a tree of range objects, chosen for cache-friendliness and low per-entry overhead on the sets this library builds most. Named classes (`\p{Alpha}`, Unicode categories, POSIX classes, `\d`/`\w`/`\s`, etc., in `NamedCharClass`/`UnicodePredicates`) and the parser's own character-class handling both build on this.

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

Not yet implemented, and gaps to close rather than design choices: `\X` (grapheme cluster). `\b{g}` is rejected rather than silently misread.
`\N{name}` looks the name up with the platform's `Character.codePointOf` (JDK 9+, Android with a recent enough ICU), so
it knows exactly the characters the running platform's Unicode data does; where that method is missing it is a compile error
suggesting `\x{...}`.
`Matcher.reset(CharSequence)` snapshots the text with `toString()`, so later changes to a mutable sequence aren't seen.
A quantifier with nothing to repeat (`*a`, `a**`) is a compile error, as in the JDK; one after a zero-width
construct (`^*a`, `\b+a`) is accepted and folded away.

### Considered and deliberately not added

- **A client-runnable Unicode generator + external data file.** Letting a client run the Unicode generator themselves and having the library prefer that data file over its built-in ranges. Rejected: substantial work and likely slower compile/match, for very little value, since the built-in data is regenerated from a newer JDK when needed. See [documents/design.md](documents/design.md)'s "Alternatives Considered".

## 3. Remaining Work

See [documents/remaining_work.md](documents/remaining_work.md) for the full, actively-maintained list. Some of the more interesting open items:

- **1-codepoint lookbehind is planned**; general lookahead/lookbehind is permanently out of scope (see "Intentional
  differences" above).
- **BUG: reluctant/possessive quantifiers are always-greedy for `find()`/`lookingAt()`** (`matches()` is
  unaffected) — e.g. `a+?` should match just `"a"` in `"aaaaa"` via `find()`, matching `java.util.regex`, but
  currently matches all 5; see remaining_work.md for the root cause and fix sketch. Needs its own careful pass,
  since it touches core loop dispatch.
- **Whether the `useTransparentBounds`/`hitEnd`-at-`regionEnd` divergence (design.md's "Boundary matching"
  section) is an acceptable, permanent consequence of the compile-time `\b`/`\B` elision, or a bug to fix** — not
  yet analyzed in depth; see remaining_work.md.
- **Syntax and API gaps** — `\X`, `\b{g}`.
- **More scraped-corpus sources planned** beyond these four — Oracle GraalVM's regex engine tests are an unconfirmed candidate; dk.brics.automaton was considered and skipped (see remaining_work.md). The RE2J corpus turned up a match-time crash (`((x))*`) and an ambiguity-check gap (`a*^a`); see remaining_work.md.
- **`ArrayCodePointSet` density experiment**: a proposed bitmask-entry variant (trading lookup speed for density on alternating-but-non-contiguous data, e.g. `isLowerCase`) hasn't been tried yet.

## 4. Current Progress

The module compiles; its test suite passes fully: **3808 tests, 0 failing** — hand-written unit/integration/differential tests, plus **3305 tests from a scraped-corpus differential harness** (compares `Ll1Pattern` against real test data mined from OpenJDK's own `java.util.regex` test suite (561 rows) RE2J's tests (1790 rows), AOSP libcore's extra rows (295, mostly the original ASCII `TestCases.txt`), dregex's own test table (412 rows, heavy on lookaround) and DataDog/java-reggie's RE2/PCRE/common-pattern test data (247 rows); more corpus sources are planned, see above) and 3305 further reference-only checks (re-verifying `java.util.regex`'s own recorded behavior against the installed JDK) that are disabled by default, hence "skipped" rather than run. See [documents/remaining_work.md](documents/remaining_work.md) for the JDK version required to run the suite and the full TODO list, and [documents/notes.md](documents/notes.md) for the bugs this harness has already found and fixed.

In brief:

- **Parsing** (`PatternParser`, `PatternConstruct`): a recursive-descent parser turns a pattern string into an AST of `PatternConstruct` nodes (unions, sequences, literals, character classes, quantifiers, boundaries, backreferences, groups). This layer is fairly mature; several real parsing bugs were found and fixed while building out the compiler (see [documents/notes.md](documents/notes.md)).
- **Code point range representation** (`CodePointSet`/`ArrayCodePointSet`): done and tested. A `RangeSet`-style pure-membership interface over Unicode code points, backed by a specialized flat-array implementation.
- **Compilation** (`PatternConstruct` → `MatcherConstruct`): working for literals, character classes, sequences, alternation (with real ambiguity detection — two `|` branches that could match the same next character are a compile-time error), quantifiers/loops (`?`, `*`, `+`, `{n,m}`), and capturing groups — including a group that's both capturing and quantified at once (e.g. `(a)*`), which correctly captures whichever iteration matched last, per real regex semantics. Boundary matching is implemented for `\b`/`\B` (with a compile-time optimization for the common case where a boundary sits next to a statically-word/non-word literal or character class), `^`/`$`/`\A`/`\Z`/`\z` (honoring `MULTILINE`/`UNIX_LINES`). Backreferences (`\1`-`\9`, `\k<name>`) are implemented, using a precise compile-time entry set computed from the referenced group so ordinary usage stays fully LL(1)-checked (see [documents/design.md](documents/design.md)'s "Backreferences" section). `\G` doesn't match a position at all — see design.md's "Boundary matching" section.
- **Matching** (`Matcher`): core API implemented and tested — `matches()`, `lookingAt()`, `find()`/`find(int)`, numbered and named group accessors, regions, `reset()`. Replacement (`replaceAll`/`replaceFirst`/`appendReplacement`/etc.), `split`, `toMatchResult` and `results` are implemented and differentially tested against `java.util.regex`, as are `hitEnd`/`requireEnd`; the bounds methods (`useAnchoringBounds`, `useTransparentBounds` and their `has...` getters) are implemented.
- **Supporting pieces**: `NamedCharClass`/`UnicodePredicates` (Unicode category/script/block support) and the `unicodeanalyzer` module (its code generator) are largely built out.
- `oldllkpattern/` holds an earlier version of the implementation, kept for reference during the ongoing refactor.

### Benchmarks

Both tables are milliseconds per pass over the scraped-corpus golden files' `AGREES` rows (lower is better), measured via JMH on desktop (`CorpusBenchmark`, [benchmarks/Intel-i7-9750H_corpus_benchmark_results.json](benchmarks/Intel-i7-9750H_corpus_benchmark_results.json)) and an instrumented on-device benchmark on Android (`AndroidCorpusBenchmark`, [benchmarks/Google_Pixel_3a_sargo_corpus_benchmark_results.json](benchmarks/Google_Pixel_3a_sargo_corpus_benchmark_results.json)). As of 2026-09-21 both benchmarks pull in every golden file under `src/test/resources/golden/` (previously only `openjdk_bmp`/`openjdk_supplementary`) -- ~2300 rows now, versus ~480 before -- so the numbers below are NOT comparable to older figures in this file's history; see remaining_work.md's benchmark sections for the full caveats, including "Benchmark methodology" for how a future performance change should be A/B'd against this new baseline.

Absolute ms/pass varies run to run with background load on either device (see notes.md); the
llkpattern/regex **ratio** column (last, in each table below) is the more stable number to track
over time.

**Corpus compile time (each pass compiles ~2300 patterns):**

| | regex (ms/pass) | llkpattern (ms/pass) | llk/regex ratio |
|---|---|---|---|
| Intel-i7-9750H | 0.635 | 1.798 | 2.83x |
| Pixel 3a | 56.85 | 42.55 | 0.75x |

**Corpus match time (each pass matches/finds/look_ats ~2300 patterns):**

| | regex (ms/pass) | llkpattern (ms/pass) | llk/regex ratio |
|---|---|---|---|
| Intel-i7-9750H | 0.302 | 0.329 | 1.09x |
| Pixel 3a | 24.13 | 5.02 | 0.21x |

llkpattern still compiles slower than `java.util.regex` on desktop (compilation does real ambiguity-detection work `java.util.regex` skips). On the Pixel 3a compile time is now somewhat faster than `java.util.regex`, though the two land close enough together, and vary run-to-run, that this ratio shouldn't be read as settled (see notes.md). Match time is faster than `java.util.regex` on the Pixel 3a, notably so; on desktop the two are close to parity, with llkpattern landing on either side of `java.util.regex` depending on run and corpus composition. See notes.md for the compile/match-time performance history.

## 5. Authorship

The original base implementation was written by [github.com/Ambeco](https://github.com/Ambeco). Claude (Anthropic) has taken over the implementation from that base, working under Ambeco's guidance and direction.
