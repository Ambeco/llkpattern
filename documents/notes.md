# Working Notes (for Claude)

Notes to self about how to work on this project, and other context that doesn't belong in the README or design docs.

## Process preferences

- The user (Ambeco) wrote the original base code; I've taken over implementation under their guidance. Don't assume I know the historical reasoning behind existing code — ask if it's unclear rather than guessing.
- Make reasonable, incremental commits as we progress, rather than one giant commit at the end.
- The user highly values comprehensive automated tests, run frequently. As of this writing (2026-09-05), the project does not yet have a working test suite (the one test file present doesn't currently compile) — this is a priority to establish, not just an afterthought.
- Default branch name for any new git repo: `main`, not `master`.

## Project history / state as of 2026-09-05

- The user hadn't touched this project in a couple of years before this session. Git repo did not exist yet — initialized fresh (`main` branch) on 2026-09-05, first commit imports everything as it was found on disk.
- Per the user: they were in the middle of two simultaneous refactors when they stopped, which they've noted (with hindsight) was probably too much to juggle at once and contributed to stalling out:
  1. Compiling the parsed AST (`PatternConstruct` tree) into a graph of executable matcher nodes (`MatcherConstruct`). This is partially done — `PatternConstruct.QuantifiedUnion.buildEntryMap` (the union/loop ambiguity-detection logic) is left mid-edit with syntax errors.
  2. Refactoring the character-class representation away from mirroring the pattern's own literal syntax, toward a `RangeSet`-based representation (`CharacterClass`/`CodePointMap`/`TreeCodePointMap`), with the stated eventual intent to replace that with something more specialized/optimized once the shape is proven out. This is also mid-flight — the old direct-`RangeSet`-on-`ComplexCharacter` approach and the new `CodePointMap` family currently coexist.
- Suggested approach going forward: tackle these one at a time rather than in parallel, given the user's own diagnosis of what stalled progress last time. Probably: get #1 compiling and minimally working first (even against the old, simpler character-class representation), get a real test suite in place around it, *then* return to #2.
- See [design.md](design.md) for the technical design writeup and open questions, and [remaining_work.md](remaining_work.md) for the concrete TODO list inferred from reading the code.

## Misc

- `oldllkpattern/` is the previous implementation attempt, kept around for reference — don't delete without checking with the user first.
- `app/` looks like unmodified Android Studio "New Project" boilerplate (example activity/tests, launcher icons, etc.) — unclear yet if it's actually used for anything; asked about in remaining_work.md.
