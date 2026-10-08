# Proposal: build the matcher graph directly from the parser (no AST, no compile step)

Status: THEORETICAL, a possible future experimental fork/rewrite, not scheduled; nothing here describes current behavior (see [design.md](design.md) for that).
Origin: project owner's idea, 2026-10-05; revised 2026-10-06 after the owner's feedback (variant A is the plan,
variant B only a fallback). Edge cases were reviewed with an advisor. This is big enough to be its own session and
its own plan; start with the prototype in "Migration".

## 1. The idea

Today: `PatternParser` -> `PatternConstruct` AST -> lazy `buildEntryMap` + `compile()` -> `MatcherConstruct` graph.
Proposed: the parser builds the `MatcherConstruct` graph itself. The goal is to eliminate the temporary AST
allocations (and with them the second walk, `EntryPointCycleException`, the `next` re-wiring and the
identity-sharing rule). Anything that reintroduces a per-construct side object is a cost against that goal.

1. `PatternParser` is an explicit state machine: cursor, flags, stacks tracking `(` nesting, capture numbering.
2. Each `parseX` reads its construct into locals, calls `parseNext()` for the rest of the pattern, receives a fully
   built `MatcherConstruct`, builds its own node pointing at it, and returns it. Reading is left-to-right,
   construction right-to-left.
3. `(` allocates a `StartLoopMatcherConstruct` whose constructor registers itself in the parser state, parses the
   rest, and assigns the result to its `final next`; the external parse method returns `next` instead if no loop was
   needed.
4. A `MutableCodePointSet` of entry points claimed so far is threaded through `parseX`; each node checks that it does
   not intersect it, adds its own code points, and passes the same set on.

## 2. The constraint behind every tricky case

A node with a `final next` needs its continuation built first, and the continuation is textually later. Only three
ways out exist: buffer what has been read (the call stack, or a record array), read out of order (pre-scan to the
matching `)` and jump), or point through a mutable-once slot (what the graph does today for loop back edges).

Consequence worth designing around: if nodes are only built while unwinding, the whole pattern has been read before
any node exists, so syntax errors come left-to-right as in `java.util.regex`, capture count and named groups are
known at build time, and ambiguity errors that depend on what FOLLOWS something come on the way up.

Facts about the current code: `\Q..\E` and `CANON_EQ` are rewritten to plain pattern text in the `PatternLexer`
constructor, so no second scan ever has to understand them; the "pre-scan" in the lexer constructor is only
`toCharArray` + `codePointAt`, not a paren table; every `MatcherConstruct` field must be `final` (CLAUDE.md),
including `entrySet`/`failedEntry`, which the base constructor assigns.

## 3. Variant A: tail-first recursion (the plan)

### How `(a|b)c` runs

- `(` pushes onto the parser's nesting stacks. The last atom of a branch calls the `|`/`)` handler instead of
  returning. `)` reads the quantifier, parses the continuation `c`, and builds the group-end structure (wrapped in an
  `EndCapture` node if capturing) from it.
- Unwinding builds the branches last-to-first, so each branch head's `failedEntry` is the next branch's head: final,
  no slot. A residual `.` branch or an end-of-find branch that is not textually last is simply built first, as the
  chain tail.

### Loops: the one place a node is referenced before it exists

The loop-back node `L` (built at `)`, before the body, because body tails point at it) must reach the loop head `S`
(built after the body). Options:

- **A1, owner's sketch: `StartLoopMatcherConstruct` allocated at `(`, whose constructor encloses the whole parse of
  body and continuation.** All fields final; `L` holds `S`. Cost: one object per `(` whether or not it turns out to be
  a loop (`(?:a|b)` and plain captures are far more common than loops; count them in the corpus before deciding),
  dropped when unneeded. Two problems to solve: the base constructor assigns `entrySet`/`failedEntry` before the body
  exists, so `S` cannot be a gated chain candidate (see below); and the greedy/reluctant class cannot be chosen
  inside `S`'s own constructor, because it depends on `exitAssertionChain(continuation)`.
  Fix for both: make `S` a thin trampoline whose only field is `final MatcherConstruct body`, assigned at the end of
  its constructor to the real gated head (`LoopMatcherConstruct`/`ReluctantLoopMatcherConstruct`, class chosen after
  the continuation is known). `L` reads `S.body`, exactly the one extra field load today's
  `continuation.matcher` marker costs. Java permits the late `final` assignment after `this` has escaped, but the
  nullness checker will object; expect `@SuppressWarnings`.
- **A2: allocate the back-edge holder only at `)` and only for a quantified group** (a small non-`MatcherConstruct`
  holder with one assign-once field, successor of today's `LoopBackPatternConstruct`). Zero garbage for non-loops;
  the cost is one deliberate non-final field, outside `MatcherConstruct`, so the all-`final` rule on nodes holds.
  Choose between A1 and A2 by corpus count of quantified vs unquantified groups, then by `jmhPaired`.

A body head that must carry the loop's gate is built before the exit node exists. Either each `parseX` can return an
unbuilt head (`build(entrySet, failedEntry)`), which every construct type must then support, or each loop pays one
extra gate node (the "separate Forking node" design.md already rejected on speed). Measure before choosing.

### Nesting stacks and the claimed set

The parser keeps, per open `(` layer (pooled `MutableCodePointSet`s reused across layers, so no per-group
allocation; copy to an immutable set only when a node's gate is finally built):

- `layerClaims`: union of the FIRST sets of the branches finished so far. A branch's leading elements are checked
  against this; at `|`/`)` the finished branch's FIRST is merged in.
- the running claimed set the owner described, threaded through `parseX`. It must NOT simply be cleared by a
  non-nullable node while aliasing `layerClaims` (`ab|ac` has to see `a` claimed when alternative 2 starts); a
  non-nullable node swaps in a fresh/pooled set for what follows it and leaves `layerClaims` untouched.
- `layerFirst`: this layer's FIRST so far (leading nullable prefix of each branch). A loop needs FIRST(body) at `)`,
  but the running set at that point only holds the LAST decision point's claims, so it cannot stand in for it.
- nullable bookkeeping: a sequence is nullable iff ALL its elements are; a union is nullable iff ANY branch is. Track
  "current branch all-nullable so far" and "any branch nullable". This is what rejects `(a?)+` at `)`.

Each built node also carries a final `canEndHere`-style flag (no required characters after it), computed from its
`next`. This is the existing `entryElse`/end-of-find notion, and it feeds the aggregate `entrySet` (nullable prefixes
folded in, see CLAUDE.md "entrySet is load-bearing"), the nullable-body test, and `exitAssertionChain`.

### Capture groups and backreferences

A backreference does not need to be referenced by later nodes; it needs data about an earlier GROUP at parse time.
Today `closedGroupsByIndex` keeps the group's AST node for that. Replace it with a per-capture-group summary filled
at `)`: FIRST set, "always exactly one code point", "can be empty". One small record per capture group (not per
`(`), and still only closed groups are visible, so forward/self references stay rejected. Backreference node type
therefore never depends on downstream data, as the owner expected. (Loops are the only node a later NODE refers to.)

### Depth

Recursion depth grows with pattern length, not nesting (today's parser loops over a sequence's atoms). A
`kw1|...|kw5000` alternation risks `StackOverflowError` on Android worker threads (1 MB). Plan: an explicit depth
counter with a `PatternSyntaxException` ("pattern too long/complex ... split it or use fewer alternatives"), set from
a measurement of per-atom stack use on ART and of the longest corpus alternation; if that limit is too low, parse flat
runs iteratively into a small array and build back-to-front in a loop, recursing only at groups.

## 4. Ambiguity checks: when each can run

| Check | When |
| --- | --- |
| Two non-nullable sibling branches | while reading, against `layerClaims` |
| Leading nullable prefix, `a?a` | while reading, against the running claimed set |
| Continuation vs the group body/branches (`(a)*a`, `(a?\|b)b`) | while reading the continuation, if its running set is seeded with FIRST(body) plus any trailing-nullable branch claims |
| Optional atom at the END of a body vs what follows the loop (`(ab?)+c`) | build time (up), against `L`'s FIRST = FIRST(body) + FIRST(continuation); both known by then |
| `(a?)+` (nullable body) | at `)` |
| Assertion between a loop and the end (`a+\B`) | build time, from the built continuation |

Error messages keep the pattern index of BOTH constructs, so build-time checks carry the earlier construct's index.
The threaded set cannot catch follow-dependent cases by itself; both mechanisms are needed. It must be a
`MutableCodePointSet` (not a `CodePointSetBuilder`; small-N builder conversions have regressed repeatedly, see
notes.md).

## 5. Edge cases

- **`.`**: its gate is its accept set minus every sibling's FIRST minus FIRST(continuation), computed at build time
  (unwinding), which is why `.|a` and `.+b` need no forward slot. Two residual claimants stay ambiguous; a possessive
  residual loop stays rejected (design.md ".: all other options").
- **Capture plus loop**: per-part gate -> `BeginCapture` -> content, built in that order, so a zero-iteration attempt
  never records a start.
- **`(?i)` without a colon, `\G`**: handled while reading; no node.
- **Lookbehind (one code point)**: body read to a set at read time; unchanged.
- **Flags**: inline `(?i:...)` flags live on the layer stack and are copied onto each node at build.

## 6. What replaces the analysis methods

| Today | New home |
| --- | --- |
| `buildEntryMap`, `buildLoopEntryMap`, `getEntryPointMap`, `entryPointState` guard | `entrySet` + `canEndHere` on each built node |
| `mergeEntryPoints`/`mergeOneEntryPoint`, `checkDisjoint`, `buildFlattenedChain` | union/loop construction on the way up, plus the claimed-set checks |
| `firstCharSet`, `skipZeroWidthEntrySet`, `admittedInteriorExitPeekSet` | the same logic over built nodes; the `checkAssertions` loop-exit case runs at loop build |
| `narrowResidualGates`, `elseIsResidual`, `elseIsEndOfFind`, `claimsEntryElse` | gate computation at loop/union build |
| `needsEntryPointBeforeMatcher`, `loopBodyTarget`, identity-sharing rule | deleted (no two-phase wiring) |
| `exitAssertionChain` / `collectExitAssertionChain` | unchanged, run on the built continuation |

The four-place wiring for a new zero-width construct (CLAUDE.md) collapses to: its entry-set contribution, its
`admittedInteriorExit` contribution, its `ZeroWidthAssertionGuard`, and its `collectExitAssertionChain` branch.

## 7. Variant B (fallback only, probably rejected): two passes over a flat record array

Allocation caveat: the records hold the same data the AST nodes do, and a growable array over-allocates, so B saves
allocation only if the arrays are pooled per thread (retains memory, re-entrancy hazard) or sized exactly by a
counting pre-pass (a second lexer that must agree with the real one). An AST node's cost is a 12-16 byte header plus
its fields, so even exact sizing is a modest win at best. Layout, if ever needed: a growable `int[]` of fixed-width
records (kind, pattern index, flags, three per-kind slots) plus a side `Object[]` for `CodePointSet`s, literal runs as
`[start,end)` ranges into the pattern's `char[]`, `(` records back-patched with their matching `)`, and per-branch
"next branch" indices; no per-record FIRST (it depends on what follows, so the build pass computes it).

Read pass fills a flat record array (kind, index, flags, min/max, set, branch/group ranges, FIRST summary); a
right-to-left build pass makes the nodes, with the loop record doing the A1-style enclosing constructor. It removes
the depth problem and makes class choice and gates trivial, but it is an AST in all but name and dramatically trickier
to write, so keep it only if variant A's depth guard proves unacceptable. An out-of-order alternative (pre-scan to the
matching `)`, parse the continuation first, rewind) is worse: it needs a second lexer that agrees with the real one on
escapes, `[]]`, `\x{..}` and `(?x)` COMMENTS, and still cannot choose the loop class.

## 8. Migration and validation

1. Keep the old pipeline. Add a graph-equivalence test over every golden-corpus pattern: same node classes, same
   `entrySet`/`failedEntry`/`next` topology and flags, same exception text.
2. Hand-check the two CLAUDE.md cases: `((a?b)c)?` vs `""` matches, and `(a+b)+` vs `"ababab"` gives
   `group(1) == "ab"`.
3. Prototype only literals, classes, groups, unions and greedy loops, then measure `jmhPaired` compile ratio and B/op
   before porting anything else. The point is compile time and allocation; if the prototype does not win, stop.
4. Port in the order: boundaries/assertions, reluctant loops, backreferences, lookbehind, grapheme constructs, `.`.

## 9. Open questions

- A1 vs A2 for the loop back edge (count quantified vs unquantified groups in the corpus).
- Unbuilt head (`build(gate)`) vs one extra gate node.
- Real stack cost per atom on ART, and the longest corpus alternation, to size the depth guard.
- Does dropping the AST reduce B/op once the pooled sets, group summaries and trampolines are counted?
