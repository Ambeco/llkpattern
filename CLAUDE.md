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
3. Prompt the user to plug in and unlock the Pixel 3a so the on-device numbers
   (`benchmarks/Google_Pixel_3a_sargo_corpus_benchmark_results.json`) can be re-run and
   updated too, rather than letting the desktop and on-device numbers drift out of sync silently.
