# Remaining Work

Run `./gradlew :llkpattern:test` (with `JAVA_HOME` pointed at a JDK 17/21 — see [notes.md](notes.md)) to check the current state of the suite; see notes.md for dated pass/fail history rather than this file.

## HIGHEST PRIORITY

- [ ] **Implement Unicode scripts** (`\p{IsScript}`/`\p{script=Script}`) — no `NamedCharClass` entry
      uses `Source.Script` at all, so every such reference throws (covered, as throwing, by
      `UnicodeClassTest`). The underlying data already exists: `unicodeanalyzer` already generated
      one range set per script in `UnicodePredicates.java` (`LATIN`, `GREEK`, `CYRILLIC`, `HAN`,
      `KHITAN_SMALL_SCRIPT`, ~160 total) -- confirmed 2026-09-07, this was previously assumed
      unimplemented from scratch. What's missing is purely the `NamedCharClass` wiring, but at
      ~160 entries, adding one enum constant per script (the pattern every other named class here
      uses) is a lot of boilerplate for one feature -- worth restructuring `NamedCharClass` away
      from one-Java-constant-per-name to a runtime `Map<String, NamedCharClass>`-style lookup
      while doing this, rather than 160 more enum constants. Large enough to be its own session.
- [ ] **Implement Unicode blocks** (`\p{InBlock}`/`\p{block=Block}`) -- unlike scripts, no
      generator support exists for this at all (confirmed 2026-09-07: no block-named constant like
      `BASIC_LATIN` anywhere in `UnicodePredicates.java`). Needs `unicodeanalyzer` work first, not
      just `NamedCharClass` wiring.

## Also remember for later (currently-unimplemented/deferred features)

- [ ] Once implemented, add the same depth of test coverage for: quotation (`\Q...\E`),
      positive/negative lookahead (`(?=...)`/`(?!...)`), positive/negative lookbehind
      (`(?<=...)`/`(?<!...)`) -- note lookahead/lookbehind are currently rejected outright at
      parse time per design.md, and independent/atomic non-capturing groups (`(?>X)`).
- [ ] Of `java.util.regex.Pattern`'s remaining compile flags -- `CASE_INSENSITIVE`, `UNICODE_CASE`,
      `DOTALL`, and now `COMMENTS` (`(?x)`, implemented 2026-09-07 -- see `CommentsFlagTest`/
      `PatternParser.skipComments()`) are implemented (both globally and correctly scoped through
      an inline `(?i:...)`/`(?s:...)`/`(?x:...)`), and `MULTILINE`/`UNIX_LINES` are now implemented
      too, but *only* as far as `^`/`$`/`\Z` (`BoundaryMatcherConstruct`) consult them --
      `.`/`\s`/etc. under `UNIX_LINES` (which characters count as line terminators for those) is
      still unaffected by the flag, so `UNIX_LINES` is only partially honored. `LITERAL` (treat the
      whole pattern string as literal text, no metacharacters) and `CANON_EQ` (Unicode
      canonical-equivalence matching) are not started at all. Neither has any test coverage or even
      a stub `flags` branch, unlike the boundary/backreference stubs above -- they're simply
      unimplemented from scratch.

## Scraped-corpus differential test harness

Design: `documents/tools/scrape_<source>.py` fetches a source project's own regex test data and
emits an "intermediate" TSV of `(pattern, flags, input[, mode])` tuples. `CorpusGenerator`
(`llkpattern/src/test/java/.../corpus/CorpusGenerator.java`, run via
`./gradlew :llkpattern:generateCorpus -Pinput=... -Poutput=... -Pmode=... -Punescape=...`) reads
that, runs each tuple through both `java.util.regex` and `Ll1Pattern`, and writes a golden TSV
(`GoldenRow`/`GoldenTsv`) with an auto-tagged `status` column (`AGREES`/`UNIMPLEMENTED: ...`/
`UNEXPECTED: ...` -- see `CorpusGenerator`'s javadoc; a first-pass heuristic, not human-verified).
`ScrapedCorpusTestBase` is a JUnit4 `@Parameterized` base class; one concrete subclass per golden
file (`OpenJdkBmpCorpusTest`, `OpenJdkSupplementaryCorpusTest`) — regenerate via the
`generateCorpus` command above after any scraping/unescaping/engine change. See notes.md for a
dated AGREES-count snapshot rather than tracking that number here.

**Next steps**:

- [ ] Human triage pass over every non-`AGREES` row in both golden files -- retag
      `EXPECTED_DIVERGENCE` vs leave as a real bug to fix, per `CorpusGenerator`'s status scheme.
      Progress (2026-09-07): triaged all `UNEXPECTED` rows (both engines completed but disagreed --
      the `UNIMPLEMENTED` rows, where llk simply doesn't support a feature yet, weren't in scope
      for this pass). Found and fixed two real bugs this way (see the "Core implementation"
      section's history in notes.md): `Matcher#attemptMatch` never reset `quantifiableCounts`/
      `captureGroups` between separate match attempts (so a loop's iteration counter leaked across
      `find()`'s internal scan positions and repeated `matches()`/`lookingAt()`/`find()` calls),
      and capturing groups were numbered in closing-paren order instead of opening-paren order for
      any nested group. Then implemented `COMMENTS` (`(?x)`, see the flags item above), which
      resolved the corpus's `(?x)`-with-whitespace rows too. 33 `UNEXPECTED` rows dropped to 12,
      then a nested-loop entry-point bug fix (see notes.md) resolved the remaining nested-loop
      rows too. What's left falls into one category, not fixed (by design, not a bug):
      - Bounded/reluctant quantifier edge cases where the engine's no-backtracking design cannot
        produce the same match `java.util.regex` does even though llk's own greedy result is
        internally consistent (e.g. `a{2,3}` against `"aaaa"`: llk cannot tell "stop at 3" from
        "keep going" without a distinguishing next character, since nothing follows the loop, so
        it hard-fails at every position where a 4th `a` follows the first three and instead matches
        the *tail* 3 `a`s rather than `java.util.regex`'s backtracked leading 3; reluctant
        (`?`) quantifiers being a documented no-op compounds this for `{n,m}?` cases). These are a
        fundamental consequence of the engine's design (see README's own "tradeoff: not every
        pattern a traditional regex engine accepts can be expressed this way"), not bugs -- should
        be retagged `EXPECTED_DIVERGENCE` with that rationale, not left as "needs investigation."
- [ ] More sources, each as its own `scrape_<source>.py` + golden file + `ScrapedCorpusTestBase`
      subclass (the pipeline already supports this cleanly):
  - [ ] **AOSP/libcore**: `https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/test/java/util/regex/`
        (note this is `libcore`, not `platform_frameworks_base`).
  - [ ] **RE2J**: `https://github.com/google/re2j/tree/master/javatests/com/google/re2j` --
        interesting as a comparison point since RE2J, like llk, is a deliberately linear-time
        (non-backtracking) engine, just via a different mechanism (Thompson NFA simulation vs
        LL(1) compile-time dispatch).
  - [ ] **dregex**: `https://github.com/marianobarrios/dregex/tree/master/src/test/java/dregex`.
  - [ ] Oracle GraalVM's regex engine tests were a candidate too -- not yet located/confirmed.
  - [ ] **dk.brics.automaton**: `https://github.com/cs-au-dk/dk.brics.automaton/tree/master/test/java/dk/brics/automaton`.
  - [ ] **DataDog/java-reggie**: `https://github.com/DataDog/java-reggie/tree/main/reggie-integration-tests/src/test/java/com/datadoghq/reggie/integration`.
- [ ] **Investigate java-reggie's `FuzzTest`** (`https://github.com/DataDog/java-reggie` --
      look under its integration-tests module) to see how it picks fuzzed inputs and decides
      pass/fail. This project considered a fuzz test for `Ll1Pattern` before and shelved it for
      exactly that reason: it wasn't clear which inputs to generate for an arbitrary pattern, or
      what the "correct" outcome even is without an oracle to compare against. java-reggie
      apparently found an answer worth copying -- read it before building anything, don't just
      copy the "fuzz" label.

## Scraped-corpus microbenchmark

Done (2026-09-07): `CorpusBenchmark` (`llkpattern/src/jmh/java/.../corpus/CorpusBenchmark.java`),
via the `me.champeau.jmh` Gradle plugin (see `llkpattern/build.gradle`'s `jmh {}` block). Loads
every golden row from `openjdk_bmp.tsv`/`openjdk_supplementary.tsv`, keeps only rows where both
engines compiled successfully (a stricter filter than `status == "AGREES"`, which also covers rows
where both engines agree by both throwing the same compile exception -- not a speed sample), and
times `java.util.regex` vs `Ll1Pattern` compile and match separately (`regexCompile`/`llkCompile`,
`regexMatch`/`llkMatch` -- matches are pre-compiled once in `@Setup` so match timing never includes
compile cost). Run via `./gradlew :llkpattern:jmh`; results print to console and are also written
as JSON to `documents/benchmarks/corpus_benchmark_results.json` (only once the *entire* run
completes -- an interrupted run leaves that file empty), meant to be committed as a baseline and
diffed against on later runs to catch regressions.

## Core implementation

- [ ] **Consider a parse-time check rejecting a quantified construct whose entire body is nullable**
      (e.g. `(a?)+`), instead of relying solely on the entry-point-computation guard added
      2026-09-08 (see design.md's "Entry-point computation vs. matcher compilation" section) to
      catch it as a compile-time `PatternSyntaxException`. Checked (2026-09-08): no such parse-time
      check exists today -- `PatternParser` has nothing recognizing a nullable quantifier body, so
      the guard is currently the *only* thing catching these patterns, not a backstop for
      parser-level rejection as originally hoped. A `NestedQuantifierCombinatorialTest` was added
      the same day to prove the guard itself is reachable and never escapes as anything other than
      `PatternSyntaxException` across ~600 nested-quantifier pattern shapes. A parse-time version
      would need a `nullable(construct)` AST recursion (a third sibling to `firstCharSet()`/
      `lastCharSet()`) and would only be a diagnostic-quality improvement (an earlier, more
      specific error message) -- not a correctness fix, since the guard already catches every case.
- [ ] Numbered backreferences only support a single digit (`\1`-`\9`) -- unlike `java.util.regex`,
      which greedily consumes further digits when enough groups exist to make them part of the
      group number (`\12` can mean group 12, not group 1 followed by literal "2"). A pattern
      needing a 10th+ backreference isn't supported yet; see `PatternParser.tryParseBackReference`.
- [ ] `MatcherConstruct.BoundaryMatcherConstruct.match(...)`: `InputBegin`/`InputEndExceptTerminator`/`InputEnd` (`\A`/`\Z`/`\z`) are implemented (position-and-surrounding-characters checks only, honoring `MULTILINE`/`UNIX_LINES` -- see design.md's "Boundary matching" section). `^`/`$` and `\b`/`\B` are separate, already-implemented pairs (`LineBoundaryConstruct`/`LineBoundaryMatcherConstruct`, `WordBoundaryConstruct`/`WordBoundaryMatcherConstruct`). `\G` isn't a position-based boundary at all and has no `MatcherConstruct` of its own -- see design.md's "Boundary matching" section.
- [ ] Remaining `Matcher`/`Ll1Pattern` API gaps: `replaceAll`/`replaceFirst`/`appendReplacement`/`appendTail`/`quoteReplacement`, `split`/`splitAsStream`, `toMatchResult`, `hitEnd`/`requireEnd`, `useAnchoringBounds`/`hasAnchoringBounds`, `useTransparentBounds`/`hasTransparentBounds` — all still `UnsupportedOperationException` stubs. None of these are needed for the scraped-corpus differential test harness above (that only needs `matches`/`find`/`group`/`start`/`end`), so lower priority than that.
- [ ] `region()`'s interaction with `hasAnchoringBounds`/`useAnchoringBounds`/`useTransparentBounds` (whether `^`/`$`/boundaries see past the region) isn't implemented at all yet -- `^`/`$`/`\A`/`\Z`/`\z`/`\b`/`\B` all currently hard-code the "opaque bounds" behavior (never look past `regionStart`/`regionEnd`), which is `useAnchoringBounds(true)`/`useTransparentBounds(false)`'s combination (the default) but not configurable to the other three.
- [ ] `PatternConstruct.compile()` is typed `@Nullable MatcherConstruct` but, now that every construct type actually builds a matcher, likely always returns non-null in practice — worth dropping the `@Nullable` (and fixing `Ll1Pattern.compile()`'s unchecked-nullable assignment).
- [ ] `PatternSyntaxException.Reference` is constructed in a couple of places (e.g. the old, since-rewritten ambiguity-detection attempt) but was never actually handled in `PatternSyntaxException.throwWithReferences` — it silently falls through to `Object.toString()` (`Reference@<hashcode>`). Either implement it (render the referenced snippet, as `CodePoint`/`CodePointReference` do) or remove it if `CodePoint`-based messages turn out to be sufficient. Current loop/union ambiguity messages avoid it, using plain indices/`CodePoint` instead.
- [ ] Migrate `ComplexCharacter`'s direct Guava `RangeSet<Integer>` usage onto `CodePointMap`, or decide it should stay separate (`ComplexCharacter` represents a single character class's ranges, which is a slightly different job than `CodePointMap`'s "ranges to values"; worth a deliberate decision rather than reflexive migration).
- [ ] **`MultiDispatchingMatcherConstruct.dispatchMap` is still a Guava `RangeMap<Integer,
      MatcherConstruct>`** -- the one Guava map left in the compiled-graph/entry-point family now
      that `PatternConstruct.entryMap` and `QuantifiedUnion.rawEntryMap` are both `CodePointMap`s
      (2026-09-08 -- see notes.md). Unlike those two, this is the actual *runtime* dispatch
      structure `Matcher` consults on every `find()`/`matches()` call (via
      `MultiDispatchingMatcherConstruct.getNext()`), not a compile-time-only structure, so migrating
      it could plausibly also matter for match-time performance, not just compile time -- worth
      investigating together with the `llkMatch` regression above, since a shared cause (or a shared
      fix) wouldn't be a coincidence.
- [ ] **`ArrayCodePointMap`/`TreeCodePointMap` immutable+builder split**: floated in the original
      design sketch for the array-backed map, but neither implementation actually has this split
      today (both are mutable-only) -- worth doing for both together if immutability is ever
      wanted, rather than giving only the newer class a shape the older one lacks.
- [ ] **`llkMatch` persistently ~+13% since the `entryMap`/`rawEntryMap` migrations, cause unknown**:
      `CorpusBenchmark`'s `llkCompile` regression is fully resolved and then some (see notes.md's
      2026-09-08 entries), but `llkMatch` (compiled-matcher runtime, not compile time) has now shown
      the same ~+12-14% bump across two independent runs (the `entryMap` migration, then again after
      the `rawEntryMap` one) against a same-run noise floor of ~+-3-5% -- consistent enough to not be
      pure noise, but nothing in either migration touches the actual match-time dispatch path
      (`MultiDispatchingMatcherConstruct.dispatchMap` is still the same Guava `RangeMap<Integer,
      MatcherConstruct>` it always was, untouched by both migrations). Leading theory, not yet
      confirmed: `CorpusBenchmark`'s `fork = 1` setting runs every benchmark method sequentially in
      one JVM, so `llkCompile`'s now much-more-compile-heavy-per-iteration workload running
      immediately before `llkMatch` in the same fork could be polluting JIT/code-cache state in a way
      that's a benchmark-methodology artifact, not a real `Matcher` regression -- worth confirming
      with an isolated single-benchmark run (`includes = ['llkMatch']`, as already done for the
      stack-profiling run) before assuming there's an actual bug to chase.
- [ ] **Followup experiment** for `ArrayCodePointMap`: shrink the range field to 10 bits and use the
    freed 11th bit as a mask-vs-range flag. When set, the 10 "range" bits are instead a bitmask of
    which of the 10 code points *after* `min` also map to this value (not required to be
    contiguous) -- lookup then has to branch on the flag and, on a mask hit, may need to check up
    to 11 candidate keys in a row (since a mask entry no longer implies contiguous coverage the
    way a range entry does), so it trades lookup speed for density. Good fit for
    alternating-but-not-contiguous data (e.g. `isLowerCase` over `0x100`-`0x137`, which alternates
    upper/lower every code point and would otherwise need one range entry per code point). Also
    worth trying a `long[]` variant with 42 range/mask bits instead of `int[]`'s 11, trading larger
    per-entry size for fewer wasted bits when ranges/gaps are long.
- [ ] `CodePointMap.ComplementCodePointMap` is only partially implemented (`entrySet`/`intersection`/`intersectionRejectingConflicts` throw `UnsupportedOperationException`) — fill in once there's a concrete caller/use case driving what's actually needed (`.` in a branching context is the likely first caller).

## Toolchain

- [ ] Pin a Checker Framework version compatible with modern JDKs (or a JDK toolchain constraint) and re-enable the nullness checker in `llkpattern/build.gradle` — currently disabled because the default-resolved 3.19.0 crashes against JDK 25's javac internals.
- [ ] Consider bumping the Gradle wrapper (currently 8.7) so it can run on newer JDKs directly, instead of requiring `JAVA_HOME` to point at JDK 17/21. Check compatibility with the Android Gradle Plugin used by `app/` first.
- [ ] Add a documented/scripted way to run `unicodeanalyzer` and regenerate `UnicodePredicates.java`, rather than the current copy-paste-and-hand-assemble process used to fix the "code too large" bug.

## Testing

- [ ] Add more parser tests covering boundaries and backreferences syntax once those are implemented — see "Also remember for later" above.
- [ ] Cross-check current `Matcher` behavior against `java.util.regex.Pattern`/`Matcher` for the subset of syntax both support beyond what's already asserted from first principles — see the scraped-corpus harness above for the systematic version of this.
- [ ] Decide on a CI setup (or at least a documented local command, given the JDK version constraint above) to run the suite "frequently" per the owner's stated preference.

## Housekeeping / cleanup

- [ ] Clarify the relationship between `llkpattern/` (current), `oldllkpattern/` (prior version, kept for reference) — is `oldllkpattern` still needed, or can it be removed/archived once the new implementation catches up?
- [ ] Clarify what the `app/` Gradle module (looks like default Android app boilerplate) is for in this project — is it a demo/harness, or leftover scaffolding from `File > New Project` that can be deleted?
- [ ] Fill in section 2 (High-Level Design) and section 3 (Current Progress) of [README.md](../README.md) in more depth as the design solidifies (still not a full design writeup in the README itself, which continues to point at design.md).

## Open Questions

- [ ] **Ambiguity-detection error quality**: `PatternConstruct.findFirstOverlap` (used by `QuantifiedUnion.buildEntryMap`'s ambiguity check) does an O(candidates × ranges) manual scan to find and report the first conflicting range, rather than using `CodePointMap.intersectionRejectingConflicts` directly — the latter throws immediately on any conflict but only carries stringified values, not the conflicting range/candidate needed for a useful `PatternSyntaxException`. Fine for realistic pattern sizes; revisit if this becomes a real cost, or if `intersectionRejectingConflicts`'s exception is ever extended to carry structured conflict info.
- [ ] **Reluctant/possessive quantifiers' permanent semantics**: the parser currently accepts and no-ops `?`/`+` quantifier modifiers (per its own comment, "reluctant and possessive quantifiers are no-ops in this Pattern"). Confirm this is the intended permanent semantic (i.e., this engine has one matching behavior, and the reluctant/possessive distinction from `java.util.regex` doesn't apply here) and document it prominently for users migrating from `java.util.regex`, rather than leaving it as an implicit consequence of "no backtracking."
