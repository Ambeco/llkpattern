# Working Notes (for Claude)

Notes to self about how to work on this project, and other context that doesn't belong in the README or design docs.

## Build / environment

- **JDK: use 17 or 21, not 25.** This machine's default `java` is JDK 25, but Gradle 8.7 (this project's wrapper version) can't run on it — the Groovy buildscript compiler crashes intermittently with `Unsupported class file major version 69`. Set `JAVA_HOME` to a JDK 17 or 21 install for any Gradle invocation, e.g. (bash) `export JAVA_HOME="C:\Program Files\Java\jdk-17"`. Don't hardcode this into the committed `gradle.properties` (machine-specific path) — a real fix would be a Gradle toolchain declaration or bumping the Gradle wrapper version, tracked in remaining_work.md.
- The Checker Framework nullness-checking plugin is currently disabled in `llkpattern/build.gradle` (commented out, `apply false` in the `plugins{}` block) because it crashes with `NoSuchMethodError` against JDK 25's internal javac APIs even when the rest of the build uses JDK 17/21 correctly — its own default-resolved version (3.19.0) predates JDK 17-era javac internals it pokes at reflectively. Needs a compatible version pinned before re-enabling, not a permanent removal.
- `./gradlew :llkpattern:test` (with JAVA_HOME set as above) is the command to run the test suite. As of 2026-09-05 it passes (1 intentionally `@Ignore`d test, 23 passing in `TreeCodePointMapTest`).

## Process preferences

- The user (Ambeco) wrote the original base code; I've taken over implementation under their guidance. Don't assume I know the historical reasoning behind existing code — ask if it's unclear rather than guessing.
- Make reasonable, incremental commits as we progress, rather than one giant commit at the end.
- The user highly values comprehensive automated tests, run frequently. As of 2026-09-05 there is a real, passing test suite (`./gradlew :llkpattern:test`, JDK 17/21 required — see above) — keep it green and keep adding to it as each piece of the compiler gets implemented, rather than letting it lag behind.
- Default branch name for any new git repo: `main`, not `master`.
- When a task turns out to require unblocking something bigger than expected (e.g. "finish CodePointMap" led to fixing a toolchain crash and a generated-file compile error before any test could even run), pause and ask before doing large mechanical surgery (like patching a 13k-line generated file) rather than assuming — see the 2026-09-05 session's `AskUserQuestion` about the `UnicodePredicates.java` "code too large" error, where the user preferred fixing the generator over a quick patch or skipping it.

## Project history / state as of 2026-09-05

- The user hadn't touched this project in a couple of years before this session. Git repo did not exist yet — initialized fresh (`main` branch) on 2026-09-05, first commit imports everything as it was found on disk.
- Per the user: they were in the middle of two simultaneous refactors when they stopped, which they've noted (with hindsight) was probably too much to juggle at once and contributed to stalling out:
  1. Compiling the parsed AST (`PatternConstruct` tree) into a graph of executable matcher nodes (`MatcherConstruct`). This is partially done — `PatternConstruct.QuantifiedUnion.buildEntryMap` (the union/loop ambiguity-detection logic) is left mid-edit with syntax errors.
  2. Refactoring the character-class representation away from mirroring the pattern's own literal syntax, toward a `RangeSet`-based representation (`CharacterClass`/`CodePointMap`/`TreeCodePointMap`), with the stated eventual intent to replace that with something more specialized/optimized once the shape is proven out. This is also mid-flight — the old direct-`RangeSet`-on-`ComplexCharacter` approach and the new `CodePointMap` family currently coexist.
- Clarified direction (2026-09-05): the user confirmed the plan is/was to finish #2 (the `CodePointMap<V>` interface, with `TreeCodePointMap` as the initial `TreeRangeMap`-backed implementation, and a more specialized/optimized implementation later) *first*, on the theory that it'll make finishing #1 (AST → matcher-graph compilation) noticeably easier — in particular, `CodePointMap`'s merge/conflict-detection (`intersectionRejectingConflicts`) is meant to be the tool `QuantifiedUnion.buildEntryMap`'s ambiguity detection builds on, rather than hand-rolling Guava `RangeMap` overlap bookkeeping.
- Done as of 2026-09-05: `CodePointMap`/`TreeCodePointMap` rewritten and tested (23 tests), module compiles and its test suite runs green. See the [479dd84](../.) commit message for the full list of what that touched, including a few unrelated pre-existing bugs fixed along the way (a real `PatternParser` typo, `UnicodePredicates.java`'s "code too large" compile error, JDK/Checker-Framework toolchain issues) because they were blocking any compilation/testing at all.
- Next up per this plan: use `CodePointMap` to actually implement `QuantifiedUnion.buildEntryMap` (currently stubbed to throw `UnsupportedOperationException`), which is redactor #1's ambiguity-detection core.
- 2026-09-05 (later same day): the user recalled and dictated their original `compile()` algorithm design in detail — self-assigning `MatcherConstruct` constructors to break cycles, compiling tail-to-front, and a deferred `MethodHandle` optimization idea now believed moot given the `RangeMap`-based dispatch. Written up in full in [design.md](design.md)'s "The `compile()` algorithm and cycle handling" section — that's the canonical reference now, not this file. Immediately following this, the plan is to rewrite `MatcherConstruct.java` to actually implement it.
- See [design.md](design.md) for the technical design writeup and open questions, and [remaining_work.md](remaining_work.md) for the concrete TODO list inferred from reading the code.

## Tooling gotchas (this dev machine, Windows + git-bash)

- The Bash tool here is git-bash; running Windows Java tools (`java -cp ...`) directly through it can silently mis-handle mixed forward-slash paths and `;`-separated classpaths. When invoking `java`/`javac` directly (outside Gradle) with an explicit classpath, prefer the PowerShell tool with native `C:\...` paths — that's what actually worked when regenerating `UnicodePredicates.java` from `unicodeanalyzer`.
- PowerShell's `Out-File -Encoding utf8` writes a UTF-8 **BOM**. If the output is Java source (or anything else that cares), strip the BOM (`\xEF\xBB\xBF`) before compiling — it silently broke the first token in the first generated file until caught by a stray parse error location.
- `./gradlew` daemons can get stuck on a stale/wrong JDK after `JAVA_HOME` changes mid-session or after a host JDK auto-updates; `./gradlew --stop` before retrying is a cheap first move when a build fails in a way that looks environmental (e.g. `Unsupported class file major version NN`) rather than a real compile error in the diff you just made — confirm by re-running the *unmodified* file/command to see if the failure predates your change.
- Git on this machine warns `LF will be replaced by CRLF` on nearly every commit — that's this repo's line-ending normalization doing its job, not an error; ignore it.

## Misc

- `oldllkpattern/` is the previous implementation attempt, kept around for reference — don't delete without checking with the user first.
- `app/` looks like unmodified Android Studio "New Project" boilerplate (example activity/tests, launcher icons, etc.) — unclear yet if it's actually used for anything; asked about in remaining_work.md.
