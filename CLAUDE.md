# Project-specific instructions

## After a performance-affecting change

When a change is intended to affect (or plausibly could affect) compile-time or match-time
performance, once the test suite is green:

1. Re-run the relevant JMH benchmark(s) on this desktop (`./gradlew :llkpattern:jmh`, JDK 17/21 for
   the Gradle daemon -- see documents/notes.md's toolchain note). The task writes
   `benchmarks/Intel-i7-9750H_corpus_benchmark_results.json` itself; update README.md's benchmark
   tables by hand with the new numbers.
2. If the change plausibly shifts *where* time is spent (not just how much), re-capture CPU
   sampling too (temporary `profilers = ['gc', 'stack:lines=4;detailLine=true']` in
   `llkpattern/build.gradle`, reverted after -- 4-frame depth, not 8: 8 was tried and came out too
   flat/diffuse to be useful) and update the relevant
   `benchmarks/Intel-i7-9750H_*_sampling.txt` file(s) -- stale sampling naming a
   since-removed method is worse than no sampling at all.
3. Check via `adb devices` whether the Pixel 3a is already plugged in and unlocked. If it is, go
   ahead and run `./gradlew :app:connectedAndroidTest` without asking first. Its benchmark/sampling
   output files under `benchmarks/` are written by the Gradle task itself (no manual pull/copy);
   double-check after the run that they actually updated (new timestamp/numbers). If the device
   isn't there (or is there but locked, so the test run would just fail/hang), skip that step and
   don't block the rest of the work on it -- but once everything else is done, remind the user to
   plug in and unlock the Pixel 3a so this step can be run.

**Sequencing the benchmark runs with other sessions.** The Intel JMH run needs the desktop CPU
quiet. If another Claude session is active (`ListAgents`), do this in order: (1) `SendMessage` it
first, asking it to stop builds/tests and heavy tabs and reply "ready" (with `notify_when_idle:
true`); (2) immediately start the Pixel 3a run, which takes a couple of minutes and doesn't need
the desktop quiet; (3) by the time it finishes, the other session's "ready" should already have
arrived -- if so, start the Intel run right away, otherwise wait for it, never start without it;
(4) message the other session when the Intel run is done so it can resume.

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

## `entrySet` is load-bearing; hand-check dispatch/capture changes

`MatcherConstruct.entrySet` is not redundant with a node's own comparison: it is the precomputed
aggregate FIRST set, folding in nullable prefixes (`buildLoopEntryMap`'s `min == 0 ? next : null`).
Before proposing to drop or inline it, see notes.md 2026-09-18 (reverted experiment).

After ANY dispatch or capture change, hand-check these two cases -- the full suite did not catch
either failure in the reverted experiment: `((a?b)c)?` vs `""` must match, and `(a+b)+` vs
`"ababab"` must give `group(1) == "ab"`.

## Regenerating `UnicodePredicates.java`

Run `UnicodeAnalyzer` with the NEWEST installed JDK (currently `C:\Program Files\Java\jdk-27`) for the
newest Unicode data -- this means sidestepping Gradle (which can't run on JDK 25+): compile via
`./gradlew :unicodeanalyzer:classes` on JDK 17, run the class directly with that JDK's `java`, and
copy its output over with CRLF. Steps: documents/notes.md, 2026-09-19 entry. From Git Bash the
Windows `java` needs classpath entries in Windows form: `cygpath -w` the guava jar (under
`~/.gradle/caches/modules-2/files-2.1/com.google.guava/`) and join with `;`.

## Ad-hoc probes against `java.util.regex`

To compare llk with `java.util.regex` on a handful of patterns, compile a scratch class (in the
scratchpad, not the repo) against `llkpattern\build\classes\java\main` plus the guava jar (add
`...\test` if you need the corpus classes), after `./gradlew :llkpattern:compileJava
:llkpattern:compileTestJava`. `PatternSyntaxException` is package-private, so from outside the
package compare `e.getClass().getSimpleName()`. A probe class holding pattern strings with
backslashes must be written with the Write tool, not a Bash heredoc (see the global Windows notes).

## Refreshing golden corpus rows

Golden files are `llkpattern/src/test/resources/golden/*.tsv`; the `status` column is
hand-triaged and must survive a refresh, so never re-run `generateCorpus` over a whole file. To
refresh specific rows, write a scratch class in package `com.tbohne.llkpattern.corpus` (needed for
`CorpusGenerator.generateRow`), compile it against `build\classes\java\{main,test}` + guava, and
read the file with `GoldenTsv.read`, replace the wanted rows with `generateRow(pattern, flags,
input, mode)`, and `GoldenTsv.write` it back. Afterward verify that only the `status` column
differs from `git show HEAD:<file>` for rows you did not mean to change.

## Editing `documents/*.md`

These files are CRLF in the working tree but LF in the index (`core.autocrlf=true`). Edit them as
bytes (`open(p, 'rb')`, normalize, edit, re-encode with CRLF), and check `git diff --stat`
afterward: a diff of ~1000 lines means the line endings were rewritten. Never put a literal `\0`
in Python source passed through Bash: the Bash tool halves backslashes, so it becomes a real NUL
byte, and git then treats the file as binary (`git ls-files --eol` shows `w/-text`). Build such
text with `chr(92)`, or use the Edit tool.

## Reading Gradle test output

Redirect Gradle output to a file and search it with `grep -a` (raw bytes in test names otherwise
give "Binary file matches"). Per-test failure messages are in
`llkpattern/build/test-results/test/TEST-*.xml`.

