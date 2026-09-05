# Design Notes

## Pipeline

`String pattern` → **`PatternParser`** (recursive descent) → **`PatternConstruct` AST** → **compile step** → **`MatcherConstruct` graph** → **`Matcher`** walks the graph against input, one code point at a time, with no backtracking.

### Parsing (`PatternParser` / `PatternConstruct`)

- Grammar is documented as regex-flavored BNF in comments at the top of `PatternParser.java`. It supports much of `java.util.regex.Pattern`'s syntax: groups (capturing, named, non-capturing), alternation, quantifiers (`?`, `*`, `+`, `{n,m}`, and their reluctant/possessive forms — accepted but treated as no-ops since this engine has no backtracking to be reluctant about), character classes (including nested `&&` intersection and `^` negation), escapes, boundaries (`\b`, `\B`, `\A`, `\G`, `\Z`, `\z`), and Unicode property/script/block/category classes (`\p{...}`).
- Lookahead and lookbehind are explicitly rejected at parse time (`throwUnexpectedChar("lookahead/lookbehind not supported...")`) because they can't be guaranteed to execute in linear time.
- Output is a `PatternConstruct` AST: `QuantifiedUnion` (alternation + optional quantifier + optional capture), `Sequence`, `LiteralString`, `ComplexCharacter` (a character class as a `RangeSet<Integer>`), `ComplexQuantifiedCharacter`, `BoundaryConstruct`, `BackReference`, `EndConstruct`.

### Compilation (AST → matcher graph)

- Each `PatternConstruct` compiles itself plus its "what comes next" continuation into one or more `MatcherConstruct` nodes. This is the LL(1)-flavored core of the design: instead of a generic NFA, each construct's `buildEntryMap` computes, from the *next* possible input code point, which `MatcherConstruct` to dispatch to — represented as a `RangeMap<Integer, MatcherConstruct>` ("entryMap") plus a catch-all `entryElse`.
- The critical invariant the compiler must enforce: for any `QuantifiedUnion` (i.e. any `|` alternation, and any quantified construct's "keep looping vs. exit the loop" choice), the entry ranges of its branches must be pairwise disjoint. If two branches could both match the same next code point, that's a compile-time `PatternSyntaxException` (ambiguous branch), not a runtime backtrack. This ambiguity-detection logic lives in `PatternConstruct.QuantifiedUnion.buildEntryMap` and is currently unfinished (see Open Questions / remaining_work.md).
- `MatcherConstruct` subclasses (in `MatcherConstruct.java`) are the compiled, executable nodes: `SingleCharMatcherConstruct`, `LiteralMatcherConstruct`, `LoopMatcherConstruct`/`EndLoopMatcherConstruct` (quantifier loop counters), `BeginCaptureMatcherConstruct`/`EndCaptureMatcherConstruct`, `BoundaryMatcherConstruct`, `BackReferenceMatcherConstruct`, `EndMatcherConstruct`. Each node holds its own `dispatchMap`/`elseDispatch` and a `match(Matcher, int peeked)` method that consumes input and recurses into `getNext(...)`.
- Matching is therefore just: start at the compiled root node, and recursively call `match()`, each node consuming zero or more code points and dispatching to the next node based on a range lookup on the next code point. No backtracking stack is needed because ambiguity was already ruled out at compile time.

### Code point range representation

- `CodePointMap<V>` (interface) + `TreeCodePointMap<V>` (its implementation, delegating to Guava's `TreeRangeMap`) is the intended long-term replacement for ad-hoc uses of Guava `RangeSet`/`RangeMap<Integer, V>` throughout this codebase. The plan (confirmed with the project owner 2026-09-05): finish this piece first, then build the AST → matcher-graph compilation step (in particular, `QuantifiedUnion`'s branch-ambiguity detection) on top of it, rather than in parallel with it — the two were being done simultaneously before, which stalled progress.
- As of 2026-09-05 this piece is finished and tested (`TreeCodePointMapTest`, 23 cases): `[min, max)` range convention throughout, immutable `Range`, `put`/`get`/`remove`/`containsKeys`, `union`/`difference`/`intersection`, `compute`/`computeIfAbsent`, `complement`, and `intersectionRejectingConflicts` — the last one specifically added for the union/loop-ambiguity use case: it's like `intersection`, but throws `ConflictingMappingException` the moment two maps disagree on the value for an overlapping code point, which is exactly "these two branches both claim this character" in LL(1) terms.
- `CharacterClass` (a thin wrapper around `ImmutableRangeSet<Integer>`) and `PatternConstruct.ComplexCharacter`'s direct use of Guava `RangeSet<Integer>` still exist independently of `CodePointMap` — whether/how these should migrate onto `CodePointMap` is open (see below).
- `TreeCodePointMap` is deliberately not optimized for the Unicode code point domain — it's a thin adapter over a general-purpose range map. The project owner's stated intent is to swap in something more specialized (e.g. exploiting Unicode block/plane structure) later, once real usage patterns are known; the `CodePointMap` interface exists so that swap doesn't require touching any caller.

### Unicode support

- `NamedCharClass` maps regex-style named classes (`\p{Alpha}`, `\p{IsGreek}`, `\p{general_category=Lu}`, etc.) to underlying predicates/ranges.
- `UnicodePredicates` is a large (~12.9k line) generated-looking data file of Unicode category/script/block predicates. `unicodeanalyzer/` is presumably the code generator that produces it from Unicode Character Database source data — worth confirming its build wiring (is it run manually, or as part of the build?).

### Public API shape

- `Ll1Pattern` and `Matcher` are designed to mirror `java.util.regex.Pattern`/`Matcher`'s public method surface, so callers can largely swap one for the other. `Matcher`'s methods are currently mostly `UnsupportedOperationException` stubs pending the matcher-graph work above landing.

## Open Questions

- **Ambiguity-detection algorithm**: `QuantifiedUnion.buildEntryMap` is currently stubbed (throws `UnsupportedOperationException`) rather than broken — now that `CodePointMap.intersectionRejectingConflicts` exists, the design question is how to use it to merge N branches' entry maps while producing a *useful* error message (which two branches conflict, and on what characters/range) rather than just catching `ConflictingMappingException` and losing that context.
- **Two character-class representations**: decide whether/when to migrate `PatternConstruct.ComplexCharacter`'s direct `RangeSet<Integer>` usage onto the `CodePointMap` family, or keep them separate for different purposes (a character class is fundamentally "ranges → is a member", not "ranges → arbitrary value", so a dedicated type may still make sense even after `CodePointMap` exists).
- **`CodePointMap`'s eventual optimized implementation**: what data structure replaces `TreeCodePointMap`'s `TreeRangeMap` delegation? (e.g. sorted-array binary search, or a trie/radix structure exploiting Unicode block boundaries.) Deliberately deferred until real usage patterns from finishing the compiler are known.
- **Backreferences and boundaries**: `BackReferenceMatcherConstruct` and `BoundaryMatcherConstruct` are unimplemented (`match()` throws). Backreferences are also noted as "not actually context-free" in the parser's grammar comments — worth revisiting whether/how they fit the LL(1) model at all, or whether they need a special-cased runtime check.
- **`find()` / partial matching**: the design as described (root node dispatch) reads naturally as `matches()`/`lookingAt()`-style anchored matching. How does unanchored `find()` (scanning start positions) fit in without reintroducing per-position backtracking cost?
- **Reluctant/possessive quantifiers**: parser currently accepts and no-ops them (per comment, "reluctant and possessive quantifiers are no-ops in this Pattern"). Confirm this is the intended permanent semantic (i.e., this engine has one matching behavior, and the reluctant/possessive distinction from `java.util.regex` doesn't apply) and document it prominently for users migrating from `java.util.regex`.
