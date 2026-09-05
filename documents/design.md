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

### Character class representation (in flux)

- Two approaches exist side by side right now:
  - The original/straightforward one: a character class is just the `RangeSet<Integer>` living directly on `ComplexCharacter` (via Guava's `TreeRangeSet`/`Range`).
  - The newer one, mid-introduction: `CharacterClass` (a thin wrapper around `ImmutableRangeSet<Integer>`) plus a custom `CodePointMap`/`TreeCodePointMap` interface — effectively a hand-rolled union of Guava's `RangeMap` and `ConcurrentMap`, keyed on code point ranges. The stated intent (per project owner) is to eventually replace this with something more specialized/optimized than a generic range-map (e.g. something that exploits properties of Unicode code point ranges specifically), once the general shape is proven out.
- Until this settles, expect some duplication/inconsistency between `PatternConstruct`'s direct use of Guava `RangeSet` and the newer `CodePointMap` family — reconciling these is open work.

### Unicode support

- `NamedCharClass` maps regex-style named classes (`\p{Alpha}`, `\p{IsGreek}`, `\p{general_category=Lu}`, etc.) to underlying predicates/ranges.
- `UnicodePredicates` is a large (~12.9k line) generated-looking data file of Unicode category/script/block predicates. `unicodeanalyzer/` is presumably the code generator that produces it from Unicode Character Database source data — worth confirming its build wiring (is it run manually, or as part of the build?).

### Public API shape

- `Ll1Pattern` and `Matcher` are designed to mirror `java.util.regex.Pattern`/`Matcher`'s public method surface, so callers can largely swap one for the other. `Matcher`'s methods are currently mostly `UnsupportedOperationException` stubs pending the matcher-graph work above landing.

## Open Questions

- **Ambiguity-detection algorithm**: `QuantifiedUnion.buildEntryMap`'s merge-and-reject-overlaps logic (comparing each branch's entry range map against the accumulated one) is unfinished and has syntax errors. Needs a clean design: how to merge `RangeMap`s while detecting/reporting the first overlapping range in a useful error message.
- **Two character-class representations**: decide whether/when to migrate `PatternConstruct`'s direct `RangeSet<Integer>` usage onto the `CodePointMap` family, or keep them separate for different purposes.
- **`CodePointMap`'s eventual optimized implementation**: what data structure replaces the interim `TreeCodePointMap`? (e.g. sorted-array binary search, or a trie/radix structure exploiting Unicode block boundaries.)
- **Backreferences and boundaries**: `BackReferenceMatcherConstruct` and `BoundaryMatcherConstruct` are unimplemented (`match()` throws). Backreferences are also noted as "not actually context-free" in the parser's grammar comments — worth revisiting whether/how they fit the LL(1) model at all, or whether they need a special-cased runtime check.
- **`find()` / partial matching**: the design as described (root node dispatch) reads naturally as `matches()`/`lookingAt()`-style anchored matching. How does unanchored `find()` (scanning start positions) fit in without reintroducing per-position backtracking cost?
- **Reluctant/possessive quantifiers**: parser currently accepts and no-ops them (per comment, "reluctant and possessive quantifiers are no-ops in this Pattern"). Confirm this is the intended permanent semantic (i.e., this engine has one matching behavior, and the reluctant/possessive distinction from `java.util.regex` doesn't apply) and document it prominently for users migrating from `java.util.regex`.
