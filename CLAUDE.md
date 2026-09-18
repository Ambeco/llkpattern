# Project-specific instructions

## After a performance-affecting change

When a change is intended to affect (or plausibly could affect) compile-time or match-time
performance, once the test suite is green:

1. Re-run the relevant JMH benchmark(s) on this desktop (`./gradlew :llkpattern:jmh`, JDK 17/21 for
   the Gradle daemon -- see documents/notes.md's toolchain note) and update
   `benchmarks/corpus_benchmark_results.json` and README.md's benchmark tables with the
   new numbers.
2. If the change plausibly shifts *where* time is spent (not just how much), re-capture CPU
   sampling too (temporary `profilers = ['gc', 'stack:lines=4;detailLine=true']` in
   `llkpattern/build.gradle`, reverted after -- 4-frame depth, not 8: 8 was tried and came out too
   flat/diffuse to be useful) and update the relevant
   `benchmarks/Intel-i7-9750H_*_sampling.txt` file(s) -- stale sampling naming a
   since-removed method is worse than no sampling at all.
3. Check via `adb devices` whether the Pixel 3a is already plugged in and unlocked. If it is, go
   ahead and run `./gradlew :app:connectedAndroidTest` without asking first. Its benchmark/sampling
   output files under `benchmarks/` are now updated automatically by the Gradle task itself -- no
   manual pull/copy needed -- but double-check after the run that they actually updated (new
   timestamp/numbers), since JMH's own desktop benchmark (step 1 above) is NOT yet wired up the
   same way and still needs its results copied in by hand. If the device isn't there (or is there
   but locked, so the test run would just fail/hang), skip that step and don't block the rest of
   the work on it -- but once everything else is done, remind the user to plug in and unlock the
   Pixel 3a so this step can be run.

**While iterating on a narrow hypothesis** (e.g. "does data structure X beat Y for an N-element
accumulation?", not yet the final design), don't run the full corpus benchmark cycle above per
variant -- it exercises the entire parse/compile pipeline, not just the operation in question, so
each round trip costs a full JMH run's wall-clock and a large JSON to re-read for a narrow answer.
Write a tiny throwaway JMH benchmark (or even a plain loop counting allocations) isolating just the
operation being compared instead; reserve the full corpus + allocation-sampling + Pixel 3a cycle for
confirming the final chosen design once the narrow question is settled. When reading
`benchmarks/*_corpus_benchmark_results.json` (700+ lines) for a specific number, grep for the
`"score"`/`"benchmark"` lines rather than reading the whole file.

## `MatcherConstruct` fields must be `final`

Every field on a `MatcherConstruct` (`MatcherConstruct.java`) and its subclasses must be `final` --
including dispatch/successor fields, not just data fields. If a node's successor genuinely can't be
known until after it self-registers to break a construction-time cycle (a loop's own back edge),
don't add a mutable (or wrapped-mutable) field to sidestep that -- indirect through a
`PatternConstruct`'s own already-mutable-once `matcher` field instead (a second, purpose-built
marker `PatternConstruct`, resolved by ordinary assignment once the real target is known), the same
mechanism every other forward reference in this codebase already relies on. See
`MatcherConstruct.LoopMatcherConstruct`'s own class doc for a worked example.
