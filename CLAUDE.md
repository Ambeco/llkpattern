# Project-specific instructions

## Low-noise ratios: `:llkpattern:jmhPaired`

For llk/regex ratios use `./gradlew :llkpattern:jmhPaired` (JAVA_HOME = JDK 17; ~45 s with the defaults 8 forks x 300 rounds x 2-pair chains; run in background) instead of
two-run eyeballing of `jmh`: it interleaves regex and llk per feature bucket and writes
`benchmarks/<machine>_paired_ratio_results.json` with 95% CIs. A/B: comparing against the COMMITTED paired JSON works for effects of ~2% or more, but not smaller ones: same code, same machine, different hours/days, the desktop interleaved compile ratio moved up to 1.3-2.2% (committed 2.246 vs four later runs 2.197/2.216/2.215/2.221; match within 0.6%, blocked compile within 1.3%; earlier cross-day agreement was within 1%). For anything you expect to move compile time by less than ~2%, do a same-session A/B instead (save the JSON, `git stash -u`, rerun,
`git checkout -- benchmarks && git stash pop`), then
`./gradlew :llkpattern:pairedCompare -Pbefore=<a.json> -Pafter=<b.json>` (flags `<--` on |t| > 99% critical; trust
the ALL rows, per-bucket rows give attribution). `-PinjectPercent=2` slows llk by 2% to validate sensitivity.
Each result has TWO ratios: `compile`/`match` (interleaved: lowest noise, use for A/B regression detection) and
`compile-blocked`/`match-blocked` (same work timed as 2*chainPairs llk passes then as many regex passes: closer to a
real single-engine workload, and charges GC to the engine that allocates). They differ systematically: desktop compile
2.25 interleaved vs 2.34 blocked (interleaving hides ~4% of llk's GC cost, and the old JMH ratio was 2.35); Pixel match
0.26 vs 0.21 (interleaving evicts llk's cache working set, penalizing llk ~20%). Quote the blocked ratio for "how fast
is llk vs regex", the interleaved one for "did this change regress". Also reported per entry: `cpuRatio` (thread-CPU
time based; equals wall ratio on the Pixel, so no background-app stealing), `gcMsPerChain`, `ratioExcludingGc`.
Android: `testPaired` alone starts cold (llk needs ~100 warmup rounds, default now 100); in a full run the sampling tests
run first. Raw data/experiments: `-Pandroid.testInstrumentationRunnerArguments.rawSamples=true` (also writes a 100 ms
cpu-frequency/battery log and round timestamps), `.pairedRounds=` etc.; `./gradlew ... -PrawOut=<abs path>` on desktop.
Gradle runs from `connectedAndroidTest` take >10 min with raw+blocked: launch detached, not via run_in_background.
Allocation (B/op) and CPU sampling still come from `jmh`. The Android equivalent is `testPaired` (same outputs).
Pass ABSOLUTE paths to `pairedCompare` (it runs from `llkpattern/`, so relative paths fail; Windows java also can't
read Bash's `/tmp`). Pause Dropbox first.

## Which benchmark files are current: `./gradlew benchmarkStatus`

Every benchmark output embeds `sourceHash: <scope>:<hash>` (a content hash of the sources that can change that device's
numbers: scope `desktop` or `android`, see `gradle/benchmark-provenance.gradle`; Android gets it from
`BuildConfig.SOURCE_HASH`, i.e. the sources the APK was actually built from; JMH's own JSON gets it injected after the
run). `benchmarkStatus` prints CURRENT / STALE / UNKNOWN (no embedded hash) per file, so a session can see e.g. that the
Pixel files are stale because the phone wasn't available. Content hash, so no commit-ordering rules: it is the same
whether the benchmark ran before or after the commit. `printSourceHash -Pscope=...` prints the value if you must
hand-embed one into a file you know matches the current sources.

## Measurement hygiene (read before trusting or reporting a number)

- **Quiet machine first.** Pause Dropbox and any backup/cleanup tool (disk activity measurably disturbs the desktop),
  `ListAgents` for other sessions, no builds or tests running. Do not touch the phone during a Pixel run.
- **A/A before believing a small delta.** Run the same code twice; `pairedCompare` should not flag the `ALL` rows. With ~20
  per-bucket rows an occasional lone bucket flag is expected; act on `ALL`, use buckets for attribution only.
- **Which ratio answers what.** Interleaved (`compile`/`match`) = lowest noise, for "did this regress". Blocked
  (`*-blocked`) = closer to real use and charges GC to the engine that allocates: judge ALLOCATION-reducing changes by the
  blocked ratio, because interleaving hides ~4% of llk's GC cost on desktop (and penalizes llk's cache footprint on the
  Pixel). A change that only shows up in blocked, or only in interleaved, needs a second look, not a verdict.
- **Resolution.** Desktop: about 1-2% from one 80 s run; compare against the committed baseline only for effects >= ~2%
  (it drifted 1-2% between sessions on identical code), otherwise same-session A/B. Android: one run resolves only ~4-5%
  (run-to-run noise is ~2x the printed CI; a 2% injected slowdown was not detected): treat a single Pixel run as a sanity
  check, and do not claim smaller Pixel effects until the multi-run harness (remaining_work.md) exists.
- **Android specifics.** A standalone `testPaired` starts cold (llk needs ~100 warmup rounds, the default); in a full run the
  sampling tests go first. Never run it while a `git stash`/checkout is in flight (APK is built from the working tree).
- **Orchestration pitfalls.** The Bash tool's background runs are killed after 10 minutes: launch longer jobs (Pixel runs
  with raw+blocked, several-run scripts) detached with PowerShell `Start-Process`, write a `.done` sentinel file, and watch
  that exact file (a Monitor that greps a shared pattern also matches older logs and fires immediately). In PowerShell
  quote every `-P...=...` argument (unquoted, `-Pa.b=c` is split at the dot) and write `${name}_x`, not `$name_x`. If you
  kill a run, also kill its gradle client (`Get-CimInstance Win32_Process`) and `adb shell am force-stop` the app.
- **Record what you measured.** Result files embed their source hash; `benchmarkStatus` must show every file CURRENT
  before you finish. `-PrawOut=` (desktop) / `rawSamples=true` (Android) keep every chain if you need to re-slice.

## After a performance-affecting change

When a change is intended to affect (or plausibly could affect) compile-time or match-time
performance, once the test suite is green:

1. Measure time with `:llkpattern:jmhPaired` (same-session A/B; see "Low-noise ratios" above and "Measurement
   hygiene" below) and allocation with `./gradlew :llkpattern:jmh` (JDK 17 for the Gradle daemon; the paired runner
   does not measure B/op). Both write their `benchmarks/` files themselves. README.md's benchmark tables still quote
   pre-paired numbers: when updating them, quote both paired ratios per device (interleaved and blocked).
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
4. Re-run `./gradlew :llkpattern:jmhAllocSampling` too, updating `benchmarks/Intel-i7-9750H_*_alloc_sampling.txt`
   -- every file under `benchmarks/` should come out of this checklist refreshed together, not just
   whichever ones an earlier, narrower version of this checklist happened to name. Before committing,
   `git status --short benchmarks/` and make sure nothing in that directory is left stale relative to
   the others (compare each file's last-commit date, e.g. `git log -1 --format=%cd -- <file>`, if unsure).

**Sequencing the benchmark runs with other sessions.** The Intel JMH run needs the desktop CPU
quiet. If another Claude session is active (`ListAgents`), do this in order: (1) `SendMessage` it
first, asking it to stop builds/tests and heavy tabs and reply "ready" (with `notify_when_idle:
true`); (2) immediately start the Pixel 3a run, which takes a couple of minutes and doesn't need
the desktop quiet; (3) by the time it finishes, the other session's "ready" should already have
arrived -- if so, start the Intel run right away, otherwise wait for it, never start without it;
(4) message the other session when the Intel run is done so it can resume.

**The Pixel 3a run also can't overlap your OWN `git stash`/`pop` (the A/B step below), even though
it "doesn't need the desktop quiet."** "Doesn't need the desktop quiet" is about CPU contention
only -- `:app:connectedAndroidTest` still builds the APK from the CURRENT working tree, so stashing
source out from under it mid-build (or popping back) can let it compile against a source state that
shifted partway through, without any error -- the result silently looks like a normal run (seen:
2026-09-24, a Pixel run kicked off just before an Intel A/B's `git stash` came back byte-identical
to the already-committed baseline, i.e. it measured nothing new; had to be discarded and rerun after
the stash/pop settled). Either run the Pixel step fully before/after the Intel A/B's stash window,
or confirm no stash/checkout touches `llkpattern/src/main`/`unicodeanalyzer` while it's in flight.

**A/B against a fresh baseline, after any change that could potentially affect performance.** The
committed `benchmarks/*` baselines can be stale, so a delta against them may not come from your change
(seen: a committed 44,616 B/op vs 48,488 B/op measured on unchanged code). Copy the new JSON somewhere,
`git stash -u`, run `./gradlew :llkpattern:jmh` (~75 s), copy that JSON, then
`git checkout -- benchmarks && git stash pop`. Compare `primaryMetric.score` and
`secondaryMetrics["·gc.alloc.rate.norm"]`. The jmh task also regenerates the `*_sampling.txt` files by
itself; the Pixel 3a run takes ~95 s. `:llkpattern:jmh` is configured (`llkpattern/build.gradle`,
`outputs.upToDateWhen { false }`) to never report UP-TO-DATE, specifically so two back-to-back runs
(e.g. two baseline samples for noise estimation) can't silently return the same stale JSON --
no `--rerun` needed, every invocation genuinely re-measures.

**After the A/B, re-run `:llkpattern:jmh` once more on the final code before updating README.**
`git checkout -- benchmarks && git stash pop` discards the "change" run's own JSON/sampling files
(they get overwritten with the pre-change baseline's), so the numbers you A/B'd against are no
longer the ones sitting in `benchmarks/` afterward -- and README's tables must match what's actually
committed there. A single JMH run also has real run-to-run noise (seen: one run's error bars were
50%+ of the score, from unrelated background CPU load); if a run looks noisy, just re-run rather than
trusting it.

**Never start a Gradle job with a trailing `&` inside a Bash tool call.** It orphans a background
process this session loses track of, which then races later `:llkpattern:jmh` invocations for the
`jmh.lock` file and fails one of them with "Another JMH instance might be running". Use the Bash
tool's own `run_in_background: true` instead, which is tracked and notifies on completion.

**Stopping a background JMH run (TaskStop) does not kill its Java process.** The orphaned
`gradlew`/JMH process keeps holding `jmh.lock`, so every later `:llkpattern:jmh` fails with "Another
JMH instance might be running" -- and a script that just `cp`s the output JSON afterward silently
"measures" one stale file N times (seen 2026-09-30: four byte-identical "runs"). Kill the orphan
before relaunching (find it with `Get-CimInstance Win32_Process -Filter "Name='java.exe'"`, stop the
`gradlew` client, then `./gradlew --stop`), and have A/B scripts print each build's `BUILD` line.

**The primary metric is the llk/regex ratio, not either absolute number.** Absolute ms/pass varies
run to run with background load on either device, but the paired ratio is stable (desktop +-1%). Use
`pairedCompare`'s `<--` flags on the `ALL` rows to decide: if neither the interleaved nor the blocked ratio shows a flagged
regression, commit and push without asking first. Only pause to ask when an `ALL` row is flagged as a regression or you
are otherwise unsure. (The JMH `primaryMetric.score` bands are only for absolute numbers and B/op.)

**While iterating on a narrow hypothesis** (e.g. "does data structure X beat Y for an N-element
accumulation?", not yet the final design), don't run the full corpus benchmark cycle above per
variant -- it exercises the entire parse/compile pipeline, not just the operation in question, so
each round trip costs a full JMH run's wall-clock and a large JSON to re-read for a narrow answer.
Write a tiny throwaway JMH benchmark (or even a plain loop counting allocations) isolating just the
operation being compared instead; reserve the full corpus + allocation-sampling + Pixel 3a cycle for
confirming the final chosen design once the narrow question is settled. When reading
`benchmarks/*_corpus_benchmark_results.json` (700+ lines) for a specific number, grep for the
`"score"`/`"benchmark"` lines rather than reading the whole file.

**Attributing a 1-2% compile regression: count call sites, don't read JFR shares.** JFR alloc-sampling
percentages are too noisy at that size (the same code swung a leaf from 3% to 13% between runs). Instead
temporarily add static counters (or a `new Throwable().getStackTrace()` histogram keyed by caller and operand
sizes) to the suspect method, and run one corpus pass from a scratch driver, reading allocation with
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` -- deterministic, takes minutes. Save the real file
first (`cp` to the scratchpad) and restore it after. Classpath for the driver: `build/classes/java/{main,test}`
plus guava, `androidx.collection:collection-jvm:1.5.0`, `kotlin-stdlib`, and `checker-util` (all `cygpath -w`'d).

**Interleave the A/B, don't batch it.** Run change, baseline, change, baseline, change (stash between)
rather than all baselines then all changes: the desktop drifts ~5% over a session (regex-only numbers moved
with no code change), which otherwise reads as a regression. Compare the llk/regex ratio per pair.

**Investigating a suspected regression via the existing sampling tasks.** When a change (or a
scraped-corpus refresh) is suspected of regressing compile or match time but the golden corpus
doesn't yet exercise the suspect pattern/input much or at all, don't just eyeball the aggregate
ratio -- temporarily mutate the corpus the relevant benchmark method itself reads (not a sibling
`@Benchmark` method) so roughly half its rows exercise the suspect pattern/input, then run
`./gradlew :llkpattern:jmh` (CPU sampling, via its `jmhSampling` `finalizedBy`) and
`./gradlew :llkpattern:jmhAllocSampling` (allocation sampling) against that mutated corpus and diff
the resulting `benchmarks/*_sampling.txt`/`*_alloc_sampling.txt` against the committed baseline.
This works because both sampling tasks call the benchmark method by its literal name
(`samplingBenchmarkNames` in `llkpattern/build.gradle`, currently `['llkCompile', 'llkMatch']`) --
a new sibling method wouldn't get sampled at all, so the mutation has to happen inside
`CorpusBenchmark.llkCompile`/`llkMatch` themselves (e.g. read from a parallel mutated
pattern/flags array built in `setUp()`, falling back to the original row when the mutation breaks
compilation). New leaves/leaf-weight jumps in the diff point straight at the regressing code path
-- this is how `PatternConstruct.universalCodePointSet`'s per-call allocation (no caching) was
found as `\X`'s real per-use cost (documents/notes.md, 2026-09-26). Revert the mutation and the
auto-overwritten `benchmarks/*` files afterward (`git checkout --`) -- this is a throwaway
diagnostic, not a real benchmark methodology change, and regexCompile/regexMatch usually can't even
run against the mutated patterns if the suspect syntax isn't valid `java.util.regex`.

## `MatcherConstruct` fields must be `final`

Every field on a `MatcherConstruct` (`impl/constructs/MatcherConstruct.java`) and its subclasses must be `final` --
including dispatch/successor fields, not just data fields. If a node's successor genuinely can't be
known until after it self-registers to break a construction-time cycle (a loop's own back edge),
don't add a mutable (or wrapped-mutable) field to sidestep that -- indirect through a
`PatternConstruct`'s own already-mutable-once `matcher` field instead (a second, purpose-built
marker `PatternConstruct`, resolved by ordinary assignment once the real target is known), the same
mechanism every other forward reference in this codebase already relies on. See
`LoopMatcherConstruct`'s own class doc for a worked example.

## `entrySet` is load-bearing; hand-check dispatch/capture changes

`MatcherConstruct.entrySet` is not redundant with a node's own comparison: it is the precomputed
aggregate FIRST set, folding in nullable prefixes (`buildLoopEntryMap`'s `min == 0 ? next : null`).
Before proposing to drop or inline it, see notes.md 2026-09-18 (reverted experiment).

After ANY dispatch or capture change, hand-check these two cases -- the full suite did not catch
either failure in the reverted experiment: `((a?b)c)?` vs `""` must match, and `(a+b)+` vs
`"ababab"` must give `group(1) == "ab"`.

## Regenerating `UnicodePredicates.java`

Run `UnicodeAnalyzer` with the NEWEST installed JDK (currently `C:\Program Files\Java\jdk-27`) for the
newest Unicode data -- this means sidestepping Gradle (which crashed intermittently on JDK 25): compile via
that JDK's own `javac` (`:unicodeanalyzer:classes` fails on JDK 17: it uses JDK 21+ `Character` APIs;
needs the guava and checker-qual jars on `-cp`), run the class directly with its `java` (add
`-Dstdout.encoding=UTF-8`), and copy its output over with CRLF. Steps: documents/notes.md, 2026-09-19 entry. From Git Bash the
Windows `java` needs classpath entries in Windows form: `cygpath -w` the guava jar (under
`~/.gradle/caches/modules-2/files-2.1/com.google.guava/`) and join with `;`.

## Differential-test workflow

Diff the full matrix, not hand-picked cases: every region `(s,e)` of short inputs x flag sets x
`matches`/`lookingAt`/`find()`-loop x the setting under test, comparing spans, `hitEnd` and `requireEnd`.
Put the first ~400 divergences in the assertion message, then read them from
`llkpattern/build/test-results/test/TEST-*.xml` (the message is HTML-escaped with literal `\n`; bucket
by pattern with a short script). This found the MULTILINE `^`-at-end rule and the elided-`\b` `hitEnd`
divergence that hand-written cases missed.

## JDK API level in tests

`llkpattern` compiles at `sourceCompatibility 8` with no `--release`, so tests see the running JDK's
newer APIs (`Pattern.splitWithDelimiters`, `Matcher.hasMatch`, both JDK 20/21+). Call them by
reflection with `assumeNoException`, so the suite still compiles and runs on JDK 17.

## Ad-hoc probes against `java.util.regex`

To compare llk with `java.util.regex` on a handful of patterns, compile a scratch class (in the
scratchpad, not the repo) against `llkpattern\build\classes\java\main` plus the guava jar (add
`...\test` if you need the corpus classes), after `./gradlew :llkpattern:compileJava
:llkpattern:compileTestJava`. `PatternSyntaxException` is package-private, so from outside the
package compare `e.getClass().getSimpleName()`. A probe class holding pattern strings with
backslashes must be written with the Write tool, not a Bash heredoc (see the global Windows notes).

**Every `-cp` entry, for both `javac` and `java`, must be `cygpath -w`'d and joined with `;`** when
invoked from the Bash tool (Windows `javac`/`java`, POSIX-style Bash) -- not just the guava jar.
Leaving even one entry (e.g. the scratchpad output dir or `llkpattern\build\classes\java\main`) as a
raw `/c/...`-style path makes Windows `java` fail with a misleading `NoClassDefFoundError`/
`ClassNotFoundException` for an unrelated class (e.g. guava's `ImmutableSet$Builder`, pulled in by
`NamedCharClass`'s static init) rather than a path-not-found error, which reads like a missing
dependency, not a path-format problem, and wastes a round trip chasing the wrong fix.

## Reading newer JDK regex behavior

To see how a newer JDK's `java.util.regex` behaves internally, unzip just that file from its source archive, e.g.
`unzip -o -q "C:/Program Files/Java/jdk-27/lib/src.zip" java.base/java/util/regex/Pattern.java` (and
`java.base/jdk/internal/lang/CaseFolding.java`), then grep it. That `unzip` runs via the Bash tool, which lands
the file under Bash's own POSIX `/tmp` view; the Grep/Read tools only see the Windows filesystem, so convert the
path with `cygpath -w` before passing it to them, or they'll report the path doesn't exist. JDK-version-dependent behavior (e.g. JDK 27's
`(?iu)` range closure) is reproduced only when a runtime probe of the host `java.util.regex` shows it, so llk
mirrors whichever JDK runs the tests -- see `CaseFolding.HOST_CLOSES_RANGES`.

## Proving a new test discriminates

After writing a test for a fix, run it against the old code with `git stash push -- llkpattern/src/main`
(keeps the new test file), then `git stash pop`. A test that also passes on old code is only a guard.

## Refreshing golden corpus rows

Golden files are `llkpattern/src/test/resources/golden/*.tsv`; the `status` column is
hand-triaged and must survive a refresh, so never re-run `generateCorpus` over a whole file. To
refresh specific rows, write a scratch class in package `com.tbohne.llkpattern.corpus` (needed for
`CorpusGenerator.generateRow`), compile it against `build\classes\java\{main,test}` + guava, and
read the file with `GoldenTsv.read`, replace the wanted rows with `generateRow(pattern, flags,
input, mode)`, and `GoldenTsv.write` it back. Afterward verify that only the `status` column
differs from `git show HEAD:<file>` for rows you did not mean to change.

Never use the Edit tool directly on a `golden/*.tsv` file -- it reliably fails to find its match
(CRLF, same underlying issue as `documents/*.md`) rather than corrupting anything, but it's a dead
end, not a fallback worth trying first; go straight to the scratch-tool approach above.

For a non-ASCII pattern/input, don't hand-retype it as a `\uXXXX` Java string to select the row --
a mistyped escape (e.g. `ぁ` "ぁ" for `ぃ` "ぃ") silently selects the wrong row instead of
erroring, and the mismatch just quietly skips the intended refresh. Dump the file's rows with their
actual code points first (`row.pattern.codePoints().forEach(...)`) and select by 0-based row index
instead of retyping the text.

## Adding a new zero-width assertion construct (`\b`, `^`/`$`, lookbehind, ...)

A new zero-width `PatternConstruct`/`MatcherConstruct` pair needs wiring into four places, not just
its own `buildEntryMap`/`buildMatcher`:

1. `buildEntryMap`: extend `ZeroWidthAssertionPatternConstruct` (or call `buildZeroWidthEntryMap(this, next)`), so the entry point is what can start what FOLLOWS the assertion. Never a bare `entryElse = this` catch-all: it hid `\b[ab]c|a`-style overlaps from the ambiguity check.
2. `PatternConstruct#skipZeroWidthEntrySet`'s `checkAssertions` branch, via an
   `admittedInteriorExitPeekSet` static helper -- without this, a loop whose exit passes through
   the new construct can silently compile an unsound ambiguity (see design.md's "Boundary matching"
   section).
3. `MatcherConstruct#collectExitAssertionChain` -- add an `instanceof` branch so a reluctant loop's
   early exit can check it.
4. Implement `ZeroWidthAssertionGuard` (`holdsHere`) for #3 to call.

Grep existing `WordBoundaryPatternConstruct`/`WordBoundaryMatcherConstruct` references across
`constructs/PatternConstruct.java`/`impl/constructs/MatcherConstruct.java` for the full pattern to mirror -- none of these four
are cross-referenced from a single doc comment, so it's easy to add the construct pair and miss one.

## Scraped-corpus `-Punescape` must match the scraper's own escaping

A scraper that GoldenTsv-escapes its intermediate output (all of `scrape_re2j.py`,
`scrape_aosp_regex.py`'s `openjdk` mode, `scrape_dregex.py`, `scrape_java_reggie.py`) needs
`-Punescape=tsv` on `generateCorpus`, not `none`. Passing `none` against an already-escaped
intermediate silently doubles every backslash in the golden file -- nothing errors, `generateCorpus`
just writes wrong-but-plausible compile exceptions for any pattern with a backslash escape. This
happened for real (`dregex.tsv`/`java_reggie.tsv`, 2026-09-21) and wasn't caught by the auto-tagged
`status` column at all. Before committing a new corpus, write a throwaway tool that re-runs
`Ll1Pattern.compile(...).matcher(...).find()` directly on every non-`AGREES` row and buckets by the
*live* exception message (see `Bucket.java` in that session's scratchpad) -- this is what actually
surfaced the mismatch.

## Editing `documents/*.md`

These files are CRLF in the working tree but LF in the index (`core.autocrlf=true`). Edit them as
bytes (`open(p, 'rb')`, normalize, edit, re-encode with CRLF), and check `git diff --stat`
afterward: a diff of ~1000 lines means the line endings were rewritten. Never put a literal `\0`
in Python source passed through Bash: the Bash tool halves backslashes, so it becomes a real NUL
byte, and git then treats the file as binary (`git ls-files --eol` shows `w/-text`). Build such
text with `chr(92)`, or use the Edit tool.

## Which JDK runs the suite

Run `./gradlew :llkpattern:test` on JDK 27 (`JAVA_HOME="C:\Program Files\Java\jdk-27"`). On JDK 21 it fails 8
Unicode-data tests (Emoji/Indic/CaseFold/CanonEq) even on unmodified code, since the generated data is
JDK 27's; don't chase those as regressions. (The JMH/Android tasks still need a JDK 17 daemon -- see notes.md.)

## Reading Gradle test output

Redirect Gradle output to a file and search it with `grep -a` (raw bytes in test names otherwise
give "Binary file matches"). Per-test failure messages are in
`llkpattern/build/test-results/test/TEST-*.xml`.

## Parser root shape

`PatternParser.parse()` unwraps a single-alternative root, so `parsed` is a bare `SequencePatternConstruct`, not a
`QuantifiedUnionPatternConstruct`. Code inspecting the root construct (e.g. `Ll1Pattern.startsWithBeginAnchor`) must
handle both shapes.

## Differential tests against `java.util.regex`

Empty patterns (and a bare `\G`) don't compile here by design ("Sequences must always match at
least one actual character"), so leave them out of differential test matrices. Patterns must also
be unambiguous (LL(1)) to compile at all.

## Multi-line edits to CRLF Java files containing backslashes

A Python heredoc through Bash silently fails to apply (backslashes get halved, so a `replace` finds
no match). Write the edit script to the scratchpad with the Write tool instead: raw `r'''...'''`
strings, assert `count == 1` per replacement, and read/write bytes with CRLF<->LF normalization.

This also applies to `cat <<'EOF'` heredocs that create new `.java` files: use the Write tool for any
Java text containing `\`.

## More Windows/tooling gotchas

- `python3` invoked via the Bash tool does not share a filesystem view with Bash's own shell -- a
  file just written with `cp`/`Write` in the same Bash call can 404 from `python3 -c "open(...)"`
  even at an absolute path `ls` confirms exists. Don't shell out to python3 to read/parse a file
  the Bash tool just produced; use `awk`/`grep` instead.
- A Java comment containing a bare `\u` or `\x` not followed by 4 valid hex digits is a javac
  compile error ("illegal unicode escape") -- javac unicode-escapes comments too, not just string/
  char literals. Write "unicode escapes" or similar in prose instead of `\u` inside a comment.

## Working habits that save round trips

- Never put text containing `\` through a heredoc or a Python string: create files and probes with Write, edit
  with Edit. This includes scratch `.java` probes and Python edit scripts (`\N`, `\u`, `\b` all break).
- Before implementing a syntax feature, write a differential matrix against `java.util.regex` (pattern shapes x
  flags x inputs) first; it finds the surprising cases in one run.
- Keep a scratchpad script that prints the failure message for a test class from
  `llkpattern/build/test-results/test/TEST-*.xml` (set `PYTHONIOENCODING=utf-8`) instead of retyping it.

## Gradle and allocation-bisecting shortcuts

- `:llkpattern:checkNullness` (and so `:llkpattern:test`) sometimes fails instantly with "Failed to clean up
  stale outputs". It is transient: re-run the same command once before investigating.
- To bisect a compile-allocation regression among several conversions, revert ONE conversion at a time and
  compare `gc.alloc.rate.norm` from a single `:llkpattern:jmh` run (~3 min). It is deterministic to ~0.1%
  (the change tree's two runs agreed to within 30 B/op), unlike timing ratios. Save the file first, restore it after.
