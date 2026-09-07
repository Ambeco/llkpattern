# Remaining Work

Updated 2026-09-06. `./gradlew :llkpattern:test` (with `JAVA_HOME` pointed at a JDK 17/21 — see [notes.md](notes.md)) passes: 1358 tests, 0 failing, 561 skipped (the scraped-corpus harness accounts for 561 golden rows × ~2 tests/row).

## HIGHEST PRIORITY

- [ ] **Give `\p{Digit}` (bare POSIX form) its own working entry.** `NamedCharClass.java` has a POSIX
      `Digit` entry *commented out* (`// Digit(d.ascii, Digit),`) because the name `Digit` is already
      taken by the `Source.UProperty` entry a few lines above (Java enum constants can't share a
      name), and that UProperty entry only allows the `Is`-prefixed access form. So there are only
      **12** usable POSIX classes today, not the 13 Oracle documents (`Lower`, `Upper`, `ASCII`,
      `Alpha`, `Alnum`, `Punct`, `Graph`, `Print`, `Blank`, `Cntrl`, `XDigit`, `Space` — no bare
      `Digit`). Needs a design decision (e.g. renaming one of the two conflicting entries, or letting
      one enum entry support more than one `Source`/prefix set) before implementing. Covered (as
      throwing) by `PosixAndJavaClassTest#posix_digit_notActuallyImplemented_throwsInstead`.
- [ ] Implement Unicode scripts (`\p{IsScript}`/`\p{script=Script}`) and blocks (`\p{InBlock}`/
      `\p{block=Block}`) — currently no `NamedCharClass` entries use `Source.Script`/`Source.Block`
      at all, so every such reference throws. Covered (as throwing) by `UnicodeClassTest`.

## Also remember for later (currently-unimplemented/deferred features)

- [ ] Once implemented, add the same depth of test coverage for: backreferences `\n` and
      `\k<name>` (see `BackReferenceMatcherConstruct`, currently a stub -- also flagged in
      design.md as "not actually context-free," may not fit the LL(1) model at all), quotation
      (`\Q...\E`), positive/negative lookahead (`(?=...)`/`(?!...)`), positive/negative lookbehind
      (`(?<=...)`/`(?<!...)`) -- note lookahead/lookbehind are currently rejected outright at
      parse time per design.md, and independent/atomic non-capturing groups (`(?>X)`).
- [ ] Of `java.util.regex.Pattern`'s remaining compile flags -- `CASE_INSENSITIVE`, `UNICODE_CASE`,
      and `DOTALL` are implemented (both globally and correctly scoped through an inline
      `(?i:...)`/`(?s:...)`); everything else is not started at all: `MULTILINE` (`^`/`$` currently
      only ever match start/end of input, never per-line -- `BoundaryConstruct`'s `LineBegin`/
      `LineEnd` matching is itself still a stub, see "Core implementation" below), `UNIX_LINES`
      (which line terminators `MULTILINE`/`.` recognize), `COMMENTS` (`(?x)` — whitespace/
      `#`-comment stripping in the pattern text; parses without error today but nothing in
      `PatternParser` actually acts on it), `LITERAL` (treat the whole pattern string as literal
      text, no metacharacters), and `CANON_EQ` (Unicode canonical-equivalence matching). None of
      these have any test coverage or even a stub `flags` branch, unlike the boundary/backreference
      stubs above -- they're simply unimplemented from scratch.

## Scraped-corpus differential test harness

Design: `documents/tools/scrape_<source>.py` fetches a source project's own regex test data and
emits an "intermediate" TSV of `(pattern, flags, input[, mode])` tuples. `CorpusGenerator`
(`llkpattern/src/test/java/.../corpus/CorpusGenerator.java`, run via
`./gradlew :llkpattern:generateCorpus -Pinput=... -Poutput=... -Pmode=... -Punescape=...`) reads
that, runs each tuple through both `java.util.regex` and `Ll1Pattern`, and writes a golden TSV
(`GoldenRow`/`GoldenTsv`) with an auto-tagged `status` column (`AGREES`/`UNIMPLEMENTED: ...`/
`UNEXPECTED: ...` -- see `CorpusGenerator`'s javadoc; a first-pass heuristic, not human-verified).
`ScrapedCorpusTestBase` is a JUnit4 `@Parameterized` base class; one concrete subclass per golden
file (`OpenJdkBmpCorpusTest`, `OpenJdkSupplementaryCorpusTest`). Currently: BMP 148/222 AGREES,
supplementary 228/339 AGREES; regenerate via the `generateCorpus` command above after any
scraping/unescaping/engine change.

**Next steps**:

- [ ] Human triage pass over every non-`AGREES` row in both golden files -- retag
      `EXPECTED_DIVERGENCE` vs leave as a real bug to fix, per `CorpusGenerator`'s status scheme.
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

## Scraped-corpus microbenchmark (idea)

- [ ] **Add a microbenchmark that compares `java.util.regex` vs `Ll1Pattern` speed over the
      scraped-corpus golden files**. Sketch: load every golden row from `openjdk_bmp.tsv`/
      `openjdk_supplementary.tsv` (and any later-added corpus files, see above), keep only rows
      where `status == "AGREES"` (comparing speed on a row where the engines disagree about
      *correctness* isn't meaningful), then time (a) `java.util.regex.Pattern.compile(...)` + the
      matching call (`matches`/`lookingAt`/`find`, per the row's `mode`) and (b) `Ll1Pattern`'s
      equivalent, and report both. Open questions to settle before building:
  - JMH vs a hand-rolled JUnit timer loop -- JMH is the right tool for real microbenchmark rigor
        (warmup iterations, fork isolation, avoiding dead-code elimination) but is a new build
        dependency/plugin; a fake-it-with-JUnit version (loop N times, discard a warmup prefix,
        report min/median/mean) is far less rigorous but zero new dependencies. Ask the project
        owner which tradeoff they want once this is picked up.
  - Compile time and match time probably need reporting separately (llk likely compiles slower --
        it's building a full dispatch graph upfront -- but may match faster per-call; a combined
        number would hide that story).
  - Needs a decision on where results go: console output only (simplest), a checked-in baseline
        file to diff against (catches regressions), or both.

## Core implementation

- [ ] **Performance: split `MatcherConstruct.dispatchMap` into two node shapes** (proposed by the project owner, not yet implemented): observe that almost every `MatcherConstruct` (all but `EndLoopMatcherConstruct`, arguably `EndMatcherConstruct`) is actually followed by exactly *one* possible next node — the current `RangeMap`-based `dispatchMap` is overkill for those and blocks JIT inlining. Proposed shape: move today's `dispatchMap` down into a `MultiDispatchingMatcherConstruct` (extended by whatever genuinely branches, e.g. `EndLoopMatcherConstruct`/loop-dispatch/union-dispatch nodes), and add a `SingleDispatchingMatcherConstruct` abstract class with `final MatcherConstruct next` (+ eventually `final MethodHandle nextMethod`, revisiting the MethodHandle idea design.md marked moot under the old all-nodes-have-a-RangeMap design) for every node that only ever has one successor — a branching node's "successor" for those still becomes a `MultiDispatchingMatcherConstruct`, so nothing loses the ability to branch, only nodes that never needed to pay for it stop paying for it. Should reduce both time and memory for the common (non-branching) case. This is a real architectural change touching most of `MatcherConstruct.java`; do it as its own focused pass, not opportunistically alongside other work.
- [ ] Implement `MatcherConstruct.BackReferenceMatcherConstruct.match(...)` (currently throws; the node itself is now correctly wired into the graph, just the runtime behavior is missing).
- [ ] Implement `MatcherConstruct.BoundaryMatcherConstruct.match(...)` (currently throws; same as above — structurally present, behaviorally stubbed). This needs matcher *state* (position, surrounding characters), not just the next code point, so it may need a different mechanism than a plain dispatch map — see design.md.
- [ ] Remaining `Matcher`/`Ll1Pattern` API gaps: `replaceAll`/`replaceFirst`/`appendReplacement`/`appendTail`/`quoteReplacement`, `split`/`splitAsStream`, `toMatchResult`, `hitEnd`/`requireEnd`, `useAnchoringBounds`/`hasAnchoringBounds`, `useTransparentBounds`/`hasTransparentBounds` — all still `UnsupportedOperationException` stubs. None of these are needed for the scraped-corpus differential test harness above (that only needs `matches`/`find`/`group`/`start`/`end`), so lower priority than that.
- [ ] `region()`'s interaction with `hasAnchoringBounds`/`useAnchoringBounds`/`useTransparentBounds` (whether `^`/`$`/boundaries see past the region) isn't implemented at all yet — moot until `BoundaryMatcherConstruct` itself works, but worth remembering once it does.
- [ ] `PatternConstruct.compile()` is typed `@Nullable MatcherConstruct` but, now that every construct type actually builds a matcher, likely always returns non-null in practice — worth dropping the `@Nullable` (and fixing `Ll1Pattern.compile()`'s unchecked-nullable assignment).
- [ ] `PatternSyntaxException.Reference` is constructed in a couple of places (e.g. the old, since-rewritten ambiguity-detection attempt) but was never actually handled in `PatternSyntaxException.throwWithReferences` — it silently falls through to `Object.toString()` (`Reference@<hashcode>`). Either implement it (render the referenced snippet, as `CodePoint`/`CodePointReference` do) or remove it if `CodePoint`-based messages turn out to be sufficient. Current loop/union ambiguity messages avoid it, using plain indices/`CodePoint` instead.
- [ ] Migrate `ComplexCharacter`'s direct Guava `RangeSet<Integer>` usage onto `CodePointMap`, or decide it should stay separate (`ComplexCharacter` represents a single character class's ranges, which is a slightly different job than `CodePointMap`'s "ranges to values"; worth a deliberate decision rather than reflexive migration).
- [ ] Eventually replace `TreeCodePointMap`'s Guava `TreeRangeMap` delegation with a more specialized/optimized code-point range structure — explicitly called out by the project owner as a later step, not needed for a first working version. The `CodePointMap` interface exists specifically so this swap doesn't require touching callers. Design sketch from the project owner:
  - Two parallel arrays: `int[] codePointKeys` and `V[] values`. Each `codePointKeys` entry is a
    bitfield -- high 21 bits the range's min code point, low 11 bits the count of *additional*
    code points after `min` that map to the same value (i.e. the range is `[min, min+count]`).
    `codePointKeys` is sorted as if unsigned. Lookup: binary- or linear-search for the last key
    `<= codePoint`, subtract that key's `min` from the input code point, and check the result is
    `<= count` (i.e. within the range) -- on a hit, return the `values` entry at the same index.
  - Immutable map type plus a separate mutable builder, same shape as `TreeCodePointMap`'s
    existing immutable/builder split.
  - Expected win: dramatically less memory than `TreeRangeMap`-backed `TreeCodePointMap`
    (no per-entry object/node overhead, two flat arrays), which should also mean better CPU-cache
    behavior during matching.
  - **Followup experiment** (once the above works): shrink the range field to 10 bits and use the
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
- [ ] The `PatternConstruct.findFirstOverlap` ambiguity check is an O(candidates × ranges) manual scan rather than using `CodePointMap.intersectionRejectingConflicts` directly, because the latter's exception doesn't carry which range/candidate conflicted. Fine for realistic pattern sizes; revisit only if it matters in practice.

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
