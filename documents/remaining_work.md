# Remaining Work

Rough inference from reading the existing code (2026-09-05) — needs confirmation/reprioritization from the project owner.

## Blocking (needed just to compile)

- [ ] Fix `PatternConstruct.QuantifiedUnion.buildEntryMap` in `llkpattern/src/main/java/com/tbohne/llkpattern/PatternConstruct.java` — has syntax errors (dangling `.` expressions, unresolved variables like `overlapMap`/`entryMapRanges`/`priorElseConstruct`, a `merge(...)` call missing its `BiFunction` arg) and calls a no-arg `patterns.get(i).buildEntryMap()` that doesn't match the abstract method's signature (`buildEntryMap(PatternConstruct next)`).
- [ ] Decide/fix `PatternConstruct.compile(...)`'s return type — `PatternSyntaxException.EndConstruct.compile` overrides it as returning `void` and throwing `NotImplementedException`, but `PatternParserTest` expects `compile(...)` to return a `MatcherConstruct`.
- [ ] `PatternConstruct.java` imports `com.sun.org.apache.xerces.internal.impl.xpath.regex.Match` and `sun.reflect.generics.reflectiveObjects.NotImplementedException` — internal JDK classes not guaranteed available/won't compile cleanly on modern JDKs. Replace with proper types (e.g. `UnsupportedOperationException`, and just delete the unused `Match` import).
- [ ] `CodePointMap.java` imports internal JDK classes (`com.sun.org.apache.bcel.internal.classfile.Code`, `jdk.internal.org.objectweb.asm.commons.Remapper`) that appear to be stray/unused — remove.

## Core implementation

- [ ] Finish the AST → `MatcherConstruct` compilation step (`PatternConstruct.compile`/`buildEntryMap` for all construct types), including the union/loop ambiguity-detection and error reporting.
- [ ] Implement `MatcherConstruct.BackReferenceMatcherConstruct.match(...)` (currently throws).
- [ ] Implement `MatcherConstruct.BoundaryMatcherConstruct.match(...)` (currently throws).
- [ ] Implement the bulk of `Matcher`'s public API (`find`, `matches`, `lookingAt`, `group(...)`, `start(...)`, `end(...)`, `replaceAll`/`replaceFirst`/`appendReplacement`/`appendTail`, `toMatchResult`, `hitEnd`, `requireEnd`, `useAnchoringBounds`/`hasAnchoringBounds`, `useTransparentBounds`/`hasTransparentBounds`, `quoteReplacement`, `beginCapture`/`endCapture`) — currently `UnsupportedOperationException` stubs.
- [ ] Decide the fate of the two parallel character-class representations (`ComplexCharacter`'s direct Guava `RangeSet` vs. the new `CharacterClass`/`CodePointMap`/`TreeCodePointMap` family) — finish or roll back the in-progress `RangeSet` → `CodePointMap` refactor. See [design.md](design.md) open questions.
- [ ] Eventually replace `TreeCodePointMap` with a more specialized/optimized code-point range structure (explicitly called out by the project owner as a later step, not needed for a first working version).

## Testing

- [ ] Establish an actual automated test suite — `PatternParserTest.java` currently has a single test, and it doesn't match the current (broken) `compile()` signature, so it doesn't compile either. The project owner has said comprehensive, frequently-run automated tests are a high priority.
- [ ] Add parser tests covering the documented grammar (groups, alternation, quantifiers, character classes/intersection/negation, escapes, boundaries, `\p{...}` Unicode classes) and its error cases (`PatternSyntaxException`s).
- [ ] Add compiler tests covering ambiguity detection (ambiguous `|` branches, ambiguous loop-exit conditions) — both the positive case (should compile) and negative case (should throw a clear `PatternSyntaxException`).
- [ ] Add matcher/end-to-end tests once matching is implemented, ideally cross-checked against `java.util.regex.Pattern`/`Matcher` behavior for the subset of syntax both support.
- [ ] Decide on a CI setup (or at least a documented local command) to run the suite "frequently" per the owner's stated preference.

## Housekeeping / cleanup

- [ ] Clarify the relationship between `llkpattern/` (current), `oldllkpattern/` (prior version, kept for reference) — is `oldllkpattern` still needed, or can it be removed/archived once the new implementation catches up?
- [ ] Clarify what the `app/` Gradle module (looks like default Android app boilerplate) is for in this project — is it a demo/harness, or leftover scaffolding from `File > New Project` that can be deleted?
- [ ] Document how/when `unicodeanalyzer` is run to (re)generate `UnicodePredicates.java`, and whether it needs re-running against a newer Unicode version.
- [ ] Fill in section 2 (High-Level Design) and section 3 (Current Progress) of [README.md](../README.md) in more depth as the design solidifies (initial pass done 2026-09-05).
