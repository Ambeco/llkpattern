# Remaining Work

Updated 2026-09-06. `./gradlew :llkpattern:test` (with `JAVA_HOME` pointed at a JDK 17/21 — see [notes.md](notes.md)) passes: 1358 tests, 0 failing, 561 skipped (the scraped-corpus harness accounts for 561 golden rows × ~2 tests/row; the rest is the comprehensive grammar coverage added 2026-09-06, see "FIXED" below).

## FIXED (2026-09-06): the `PatternParser` hang on octal/`\x{...}` escapes in a `[...]` range

Root cause was two bugs in `PatternParser.parseComplexCharacter()`'s per-character `switch`:

1. `case '\\':` — when `tryParseSingleCharEscape()` successfully parsed an escape (octal `\0nn`,
   `\xhh`, `\x{h...h}`, `\uhhhh`), the result was always added as a lone `Range.singleton`, never
   checking for a following `-` to start a range (unlike the `default:` case, which already did
   this for unescaped characters). So in e.g. `[\042-\044]`, the `-` was left completely unconsumed
   after `\042`.
2. `case '-':` never called `advance(1)` and had no `break`, so on the *next* loop iteration that
   leftover `-` hit `case '-':` again, which added a literal `-` to the range set but still didn't
   advance `index` — spinning on the same character forever. (It also had no `break`, silently
   falling through into `case '\\':`'s body on every hit, though the missing `advance` was the
   actual hang.)

Fixed by making `case '\\':` call `parseMaybeRangePredicate()` when an escape is followed by `-`
(mirroring `default:`), and adding the missing `advance(1)` to `case '-':`. See
[PatternParser.java:426](../llkpattern/src/main/java/com/tbohne/llkpattern/PatternParser.java:426).

After the fix, the 4 previously-hanging golden rows (which had been auto-tagged
`PATHOLOGICAL_INPUT_SIMPLIFIED` with a stale `RuntimeException` expectation from when generation
also hung) were regenerated against their original un-shrunk input: 2 now `AGREES`, and 2 turned up
a **new, real** `UNEXPECTED` divergence — see the next item.

## FIXED (2026-09-06): llk matched into the low half of a *valid* surrogate pair

This entry originally (see git history) mischaracterized the bug as "llk matches a lone low
surrogate that `java.util.regex` rejects." **That framing was wrong** -- the golden row in question
(`[\x{dc00}-\x{dfff}]` vs an input JUnit's display name rendered as `"?"`) does not actually contain
a lone surrogate at all: its real input is the single Java string literal for U+1F4A9 (💩), i.e. a
*valid, correctly-paired* UTF-16 surrogate pair. `"?"` in the test name is just how JUnit renders an
unprintable/astral character, not evidence of what the string actually contains -- decoding the raw
golden-file bytes (`GoldenRow.input`) was needed to see this.

Before trusting either of two different AI assistants' secondhand claims about the exact rule here
(one claimed `\x{...}` behaves differently from `\u`/disjunction/`\p{Cs}` escapes; the other cited
JDK-8149446 -- a "Won't Fix" bug about matching into a valid pair -- as still-current behavior),
both were checked directly against the JDK actually installed here (17):

```
[\udc00-\udfff]        valid pair (U+10000)      find=false
[\udc00-\udfff]        lone low surrogate        find=true
[\x{dc00}-\x{dfff}]    valid pair                find=false
[\x{dc00}-\x{dfff}]    lone low surrogate        find=true
[\udc00\udc01\udc02]   valid pair (disjunction)  find=false
[\udc00\udc01\udc02]   lone low surrogate        find=true
\p{Cs}                 valid pair                find=false
\p{Cs}                 lone low surrogate        find=true
```

Neither AI's account held up: all four forms behave *identically* on this JDK -- a lone surrogate
matches, a valid pair is never split, full stop, no escape-form-specific exception. So the real,
narrow bug was: **llk's `find()` tried every UTF-16 char index as a candidate match-start position,
including the low half of a valid surrogate pair** ([Matcher.java](../llkpattern/src/main/java/com/tbohne/llkpattern/Matcher.java)'s
`find(int start)` loop incremented `i` by a plain char index, with no check for "is this the middle
of a code point"). At that index, `codePointAt(i)` returns just the lone low-surrogate char value
(it only combines a pair when called *at the high surrogate's own index*), so a character class
covering the surrogate range matched it -- exactly the JDK-8149446 defect, just not one `java.util.regex`
itself currently exhibits.

Fixed by having `find(int start)` skip any `i` that is the low half of a valid high+low pair (see
the fix's own comment for why this is scoped to `find`'s scan and not `peek`/`consume1CodePoint`,
which are already code-point-aware for within-match advancement via `Character.charCount`).

**Two more bugs surfaced while fixing this one** (both real, both pre-existing, neither introduced
by the fix -- it just stopped a different bug from masking them):

1. **Negated character classes weren't clamped to the valid Unicode domain.** Every `.complement()`
   in this codebase (`[^...]`, `.`, and built-ins like `\D`/`\S`/`\W`) produces a Guava `RangeSet`
   that's mathematically unbounded (extends to `Integer.MIN_VALUE`/`MAX_VALUE` -- Guava has no
   concept of "the codepoint domain"). Left unclamped, such a range can swallow `-1`, the sentinel
   `Matcher` uses throughout for "no more input" (see `Matcher#peek`), making end-of-input look like
   a match against a negated class and then crash trying to consume a code point past the end of the
   string (`StringIndexOutOfBoundsException` from `consume1CodePoint`). This was unreachable before
   the `find()` fix above because `find()` would always find its own (wrong) match at the mid-pair
   position first, before ever reaching a position where this could trigger. Fixed by adding
   `ComplexCharacter#validRanges()` (clamps to `[0, Character.MAX_CODE_POINT]`) and routing every
   place that turns `ranges` into an actual dispatch/entry map
   ([MatcherConstruct.java](../llkpattern/src/main/java/com/tbohne/llkpattern/MatcherConstruct.java)'s
   `SingleCharMatcherConstruct`, and two spots in
   [PatternConstruct.java](../llkpattern/src/main/java/com/tbohne/llkpattern/PatternConstruct.java))
   through it instead of raw `ranges.asRanges()`.
2. **`\p{...}`/`\P{...}` crashed with `StringIndexOutOfBoundsException` if it was the very last thing
   in the pattern** (e.g. the bare pattern `\p{Cs}`) -- `PatternParser.parseComplexEscape()` read
   `pattern.charAt(index + 1)` unconditionally, with no bounds check (unlike every other
   lookahead-by-one in that file). Fixed by bounds-checking it the same way.

Regression coverage: [SurrogateMatchingTest.java](../llkpattern/src/test/java/com/tbohne/llkpattern/SurrogateMatchingTest.java)
covers all four escape forms above, both surrogate halves, out-of-order (unpairable) surrogates, and
`find()` specifically refusing to start mid-pair. Both scraped-corpus golden files were regenerated;
every row this fix touched moved to `AGREES` or to an already-tracked, unrelated known gap (mostly
`\p{InGreek}`-style Unicode block names, and the already-documented "reluctant/possessive quantifiers
are no-ops" design decision -- see design.md).

## FIXED (2026-09-06): character-class intersection (`&&`) was entirely broken

Three separate bugs in `PatternParser.parseComplexCharacter()`, all in the `&&`/nested-class
handling, combined to produce the `UNEXPECTED`/`UNIMPLEMENTED` rows reported above:

1. **Intersection was actually computing set *difference***:
   `complex.ranges.removeAll(parseComplexCharacter().ranges)` -- Guava's `RangeSet.removeAll(other)`
   removes `other` from the set (i.e. `complex - other`), not `complex ∩ other`. Since two
   *disjoint* ranges have nothing to remove from each other, `[あ-い&&[ぅ-ぇ]]` left `[あ-い]`
   untouched instead of correctly becoming empty. Fixed via the standard trick
   `A ∩ B == A - complement(B)` (a private `intersect()` helper).
2. **A nested class as an operand had no parser case at all**: `[[あ-い]&&[ぅ-ぇ]]` (or any union
   like `[a-c[p-z]]`) has no `case '['` in the class-body switch, so a literal `[` fell into
   `default:`, was consumed as an ordinary code point, and the class closed on the very next
   unrelated `]` -- corrupting the rest of the pattern into a `PatternSyntaxException`. This was
   the single largest contributor: 35-47 rows per golden file, all `\p{...}`-free nested-bracket
   shapes. Fixed by adding `case '[':` that unions the nested class's ranges into the current
   operand.
3. **`&&` was only recognized when immediately followed by `[`**: real `java.util.regex` treats
   `&&` as the intersection operator unconditionally, with the right-hand operand being *whatever
   run of members follows, up to the next `&&` or the closing `]`* -- bracketed or not (verified
   against a real JDK: `Pattern.matches("[あ-い&&あ-ぅ]", "あ")` is `true`). llk required `&&[`,
   so `[あ-い&&ぅ-ぇ]` (no bracket around the RHS) silently degraded into two literal `&`
   characters unioned with a range, rather than an intersection. Fixed by restructuring the
   class-body loop around `&&`-separated "operand runs": `complex.ranges` now accumulates only the
   *current* run, and each `&&` folds the completed run into a running `intersectionSoFar` via the
   same `intersect()` helper, finalized at the closing `]`.

All three are on [PatternParser.java](../llkpattern/src/main/java/com/tbohne/llkpattern/PatternParser.java)'s
`parseComplexCharacter()`. After the fix and regenerating both golden files, every `&&` row in both
files is `AGREES` except 19 (4 BMP + 15 supplementary) that fail for an unrelated, pre-existing
reason: `\p{InGreek}`-style Unicode *block* names aren't implemented (confirmed via a standalone
repro -- the exception is literally `unknown named character class "InGreek"`, nothing to do with
`&&`). That gap is real but out of scope here; it belongs with the `\p{...}`/block/script coverage
item under "HIGHEST PRIORITY" below.

## FIXED (2026-09-06): CASE_INSENSITIVE/UNICODE_CASE, inline flag toggles, and DOTALL

All three had no effect on matching at all before this fix (`CASE_INSENSITIVE`/`UNICODE_CASE`
silently ignored; inline flag toggles like `(?i)` threw a `PatternSyntaxException` on *any* use;
`.` always matched everything, i.e. behaved as if `DOTALL` were permanently on). Fixed together
since finding one immediately led to the next -- see below for what's still left after this pass.

1. **`CASE_INSENSITIVE`/`UNICODE_CASE`**: fixed at match time, not by expanding character-class
   ranges at compile time. `MatcherConstruct#getNext` (the single funnel every character-based
   dispatch goes through -- character classes, `.`, alternation/loop entry) now retries the
   dispatch-map lookup with the input code point's other-case form(s) on a miss, when
   `CASE_INSENSITIVE` is set; ASCII-only folding (`a-z`/`A-Z`) unless `UNICODE_CASE` is also set,
   in which case it uses `Character.toUpperCase`/`toLowerCase`. `LiteralMatcherConstruct` (whose
   own characters are compared directly, not through a dispatch map) got the same treatment via a
   new shared `codePointsMatch()` helper. This required making `Matcher.pattern` package-private
   (was `private`) so `MatcherConstruct` can read `matcher.pattern.flags()`. See
   [MatcherConstruct.java](../llkpattern/src/main/java/com/tbohne/llkpattern/MatcherConstruct.java).
   **Scope note**: at the time this landed, it only covered flags passed to
   `Ll1Pattern.compile(pattern, flags)` (the global case), not an inline `(?i:...)` scoped to part
   of a pattern -- see the "inline flag toggles didn't actually locally scope anything" FIXED entry
   below for that follow-up, done later the same day.
2. **Inline flag toggles threw unconditionally**: `PatternParser`'s "is this flag already set"
   checks used `|` instead of `&` (`(enableFlags | flagValue) != 0` is true as soon as `flagValue`
   is nonzero, i.e. on the very first flag character of literally any `(?...)` construct). Fixed to
   `&` in both the enable and disable loops.
3. **`.` always matched everything, ignoring `DOTALL` entirely**: the `case '.':` branch in
   `PatternParser.parseUnion` built `TreeRangeSet.<Integer>create().complement()` (= "everything")
   unconditionally -- the `Ll1Pattern.DOTALL` constant existed but nothing anywhere ever read it.
   Fixed to build "everything except `\n`" unless `DOTALL` is set (a fuller line-terminator set --
   `\r`, U+0085, U+2028, U+2029 -- and `UNIX_LINES` interaction are a follow-up, not done here).

**Two more bugs surfaced while fixing these** (both real, both pre-existing, found because fixing
#2 let previously-rejected patterns reach code paths nothing had ever exercised before):

- **A flags-only group (`(?s)`, no `:`) defaulted to `captureConstructIndex = 0`** (meaning "real
  capturing group 0") instead of `-1` (non-capturing) -- unlike the plain `(?:...)` case, which
  already special-cases this. Symptom: any pattern with a real capturing group *and* a flags-only
  group crashed at match time with `ArrayIndexOutOfBoundsException` in
  `BeginCaptureMatcherConstruct` (the capture-groups array was sized for the real groups only, but
  something also tried to write into a phantom "group 0" slot). Fixed by setting
  `union.captureConstructIndex = -1` in that branch, same as `(?:...)`.
## FIXED (2026-09-06): a *bare* flags-only group (`(?s)`, no body) broke the surrounding sequence

Found while regression-testing the capture-numbering fix above. `Ll1Pattern.compile("(?s)abx")`
no longer crashed, but `matcher("abx").find()` incorrectly returned `false` (should trivially be
`true` -- `(?s)` toggling `DOTALL` shouldn't affect matching "abx" at all, let alone break it). The
bare form returns an empty, un-parsed `QuantifiedUnion` (zero `constructs`) directly from
`PatternParser.parseGroup()`, without ever going through the machinery (`parseUnion`,
`buildEntryMap`/`buildMatcher`) that makes a `QuantifiedUnion` behave as a proper link in the
compiled graph -- an empty `constructs` list didn't compile into a working no-op passthrough:
`QuantifiedUnion.buildEntryMap()` fed the empty list to `compileAndMergeCandidates()`, producing an
empty `entryMap`/`entryElse`, which `buildMatcher()` then wrapped in a `DispatchMatcherConstruct`
that matched nothing at all. Fixed by special-casing `constructs.isEmpty()` in
`QuantifiedUnion.buildEntryMap()` ([PatternConstruct.java:234](../llkpattern/src/main/java/com/tbohne/llkpattern/PatternConstruct.java:234)):
alias this construct's `entryMap`/`entryElse`/`matcher` directly to `next`'s, the same zero-width
passthrough an empty `Sequence` element already gets. Every other path to a `QuantifiedUnion`
(a real `()`/`(?:)`/`(?<name>)`) goes through `parseUnion()` first, which already rejects a
genuinely empty body (`throwEmptySequence`) before a `QuantifiedUnion` with zero `constructs` can
exist -- so a bare flags-only group is the only way to reach this case, and it's always
non-capturing (the `<name>`/`:` forms both call `parseUnion()`). See
`bareFlagsGroup_isZeroWidthNoOp()` and friends in `CaseInsensitiveTest.java`.

## FIXED (2026-09-06): inline flag toggles (`(?i:...)`, `(?i)`) didn't actually locally scope anything

`(?i:...)`/`(?i)` only ever mutated `PatternParser`'s own `flags` field at *parse* time, affecting
every construct parsed after that point in the current scope -- they were never stored
per-construct for use at match/compile time. So `(?i:abc)def` made matching sensitive to case for
`abc` correctly (by cascading), but `abc(?i:def)ghi` wrongly made `ghi` case-insensitive too,
because nothing restored the "insensitive-ness" boundary at *runtime* the way it already did at
*parse* time (`union.tempFlags`/`parentFlags` correctly restored `PatternParser.flags` after a
`(?i:...)` group for parsing purposes, e.g. deciding ASCII vs Unicode named classes -- but
`CASE_INSENSITIVE`'s match-time folding read the single global `Matcher.pattern.flags()`, which
doesn't vary by position in the pattern at all).

Fixed by giving both `PatternConstruct` and `MatcherConstruct` their own local `flags` snapshot,
instead of reading the pattern-wide `Ll1Pattern.compile(pattern, flags)` flags at match time:

- `PatternConstruct` gained a mutable `flags` field (not a constructor param, to avoid touching
  every existing subclass constructor's signature); `PatternParser` stamps it onto every
  `ComplexCharacter`/`LiteralString`/`QuantifiedUnion`/`ComplexQuantifiedCharacter` right at
  construction, using whatever `PatternParser.flags` is in scope at that point -- i.e. after an
  enclosing `(?i:...)`'s toggle has been applied and before it's restored. The quantified case
  (`ComplexQuantifiedCharacter`, and a group's own `QuantifiedUnion`) is centralized in
  `PatternParser.parseQuantifiable()` rather than at each call site.
- `MatcherConstruct` gained a `final int flags` field, populated from `owner.flags` in its
  self-registering constructor (a new `MatcherConstruct(int flags)` constructor covers the
  no-owner/synthetic nodes -- `LoopMatcherConstruct`, `EndLoopMatcherConstruct`, the no-owner
  `BeginCaptureMatcherConstruct`/`DispatchMatcherConstruct` variants -- which now take the owning
  construct's `flags` explicitly from their caller). `getNext()`'s case-fold retry and
  `codePointsMatch()`'s call sites now read this local `flags` instead of `matcher.pattern.flags()`.

See `inlineFlagGroup_scopesCaseInsensitivityToJustTheGroup()` and friends in
`CaseInsensitiveTest.java` (covering both the literal-string and character-class dispatch paths,
since they go through different `MatcherConstruct` subclasses), plus regenerated golden-corpus
counts below.

## FIXED (2026-09-06): `NamedCharClass`/`RegexCharacterClass` circular static initialization

Found (and initially only worked around) while attempting the `DOTALL` fix above:
`RegexCharacterClass`'s enum body needed `NamedCharClass.White_Space` (`s`'s Unicode variant), and
separately `NamedCharClass.Space` read back `RegexCharacterClass.s.ascii` -- a genuine two-way
dependency between the two enums. Whichever class's static initializer ran *second* saw the other's
not-yet-assigned enum constant as `null`, throwing
`NullPointerException`/`ExceptionInInitializerError`. This had never been triggered before because
every existing code path happened to cause `NamedCharClass` to finish initializing first (e.g. any
`\p{...}` lookup); reusing `RegexCharacterClass.DOT` for the `DOTALL` fix above was the first path
to load `RegexCharacterClass` *first*, reproducing the crash (confirmed via a standalone repro
before fixing: `Ll1Pattern.compile("a.b").matcher("a\nb").find()` with `case '.':` referencing
`RegexCharacterClass.DOT.get(flags)` threw `ExceptionInInitializerError` ->
`NullPointerException: Cannot read field "ascii" because "...RegexCharacterClass.s" is null` at
`NamedCharClass.java:244`).

**Root cause, precisely** (diagnosed by the project owner, 2026-09-06): `NamedCharClass.Space` was
the *only* place the outer enum read anything from `RegexCharacterClass` (`RegexCharacterClass.s`,
which itself already read `NamedCharClass.White_Space` and had its own hardcoded ASCII whitespace
literal). Two dependency edges running in opposite directions between the same two classes is
exactly what makes a cycle; `RegexCharacterClass.d` reading `NamedCharClass.Digit` was never a
problem on its own, because that edge runs the same direction as everything else. Fixed by
inlining `RegexCharacterClass.s`'s hardcoded ASCII whitespace literal directly into
`NamedCharClass.Space` (removing the one and only reverse-direction edge), then having
`RegexCharacterClass.s` read `Space.ascii`/`Space.unicode` instead of duplicating that literal
itself -- so the dependency now flows one way only (`RegexCharacterClass` depends on
`NamedCharClass`, never the reverse), and the single source of truth for the ASCII whitespace set
is `NamedCharClass.Space`, not a value duplicated in two places. See
[NamedCharClass.java](../llkpattern/src/main/java/com/tbohne/llkpattern/NamedCharClass.java)'s
`Space` and `RegexCharacterClass.s` entries. `PatternParser`'s `.`/`DOTALL` construction now reuses
`RegexCharacterClass.DOT.unicode` directly instead of the inline workaround it used before this fix
(deliberately `.unicode`, not `.get(flags)` -- `DOT`'s single-arg constructor auto-derives `.ascii`
as an ASCII-only intersection, which is correct for a POSIX/Unicode-property class like `\s` but
would make `.` wrongly stop matching non-ASCII characters by default; confirmed via the corpus,
which caught this exact mistake on the first attempt -- 12 rows with non-ASCII/supplementary input
newly failed until switched from `.get(flags)` to `.unicode`).

## FIXED (2026-09-06): comprehensive grammar test coverage found and fixed eight more real bugs

Adding the parser/compiler/matcher test coverage tracked below (escapes, character classes,
predefined/POSIX/Java/Unicode classes, `\R`, quantifier modifiers, named/non-capturing groups) found
**eight** more real, previously-latent bugs -- consistent with notes.md's standing prior that each
new kind of test finds something the previous kind couldn't see:

1. **`\p{IsXxx}`/`\p{InXxx}`/`\p{javaXxx}` prefix detection read the wrong variable.**
   `PatternParser.parseComplexEscape()` branched on `peek`/`peek2` to detect the `Is`/`In`/`java`
   prefix, but `advance(end - index + 1)` just above had already moved the parser's lookahead PAST
   the whole `\p{...}` construct -- so `peek`/`peek2` were the character(s) *following* the escape,
   not the class name's own first characters. Every `Is`/`In`/`java`-prefixed class
   (`\p{IsAlphabetic}`, `\p{javaLowerCase}`, etc.) failed with "unknown named character class"
   unless the pattern happened to continue with a coincidentally-matching character. Fixed to check
   `charClassName` itself (captured before the advance).
2. **`\P{...}` never actually negated anything.** `parseComplexEscape()` computed `boolean positive
   = peek == 'p'` but never read it again -- `\P{Lu}` behaved exactly like `\p{Lu}`. Fixed to
   complement the named class's ranges when `!positive`.
3. **The `\p{...}` name-length/first-character validation was off by one.** The name-scanning loop
   incremented its cursor *before* reading each character, so it never validated the name's own
   first character at all, and its "the name must be non-empty" check compared against the wrong
   constant -- rejecting every genuinely valid *single-letter* category name (`\p{L}`, `\p{M}`,
   `\p{N}`, `\p{P}`, `\p{S}`, `\p{Z}`, `\p{C}`) as if it had no name. Restructured to check-then-advance.
4. **`\p{...}` names couldn't contain `_` at all**, so `\p{IsWhite_Space}`, `\p{Hex_Digit}`, and the
   `general_category=`/`script=`/`block=` prefixes themselves (whose own literal text contains `_`)
   were rejected before ever reaching the name-lookup logic. Fixed by allowing `_` in the
   name-character set.
5. **`Hex_Digit` (`NamedCharClass.java`) covered the entire a-z/A-Z alphabet**, not just a-f/A-F --
   so `\p{XDigit}` wrongly matched every letter, not just hex digits. Fixed the ranges (and their
   fullwidth equivalents) to a-f/A-F only.
6. **The bare `{n}` (exact-count, no comma) quantifier never consumed its own closing `}`.** Only
   the `{n,...}` branch checked for and consumed `}`; `a{3}` left a literal, unconsumed `}`
   character immediately after the quantifier, so it only actually matched `"aaa}"`. Fixed by
   adding the missing check/consume for the no-comma form.
7. **Possessive quantifier suffixes (`a++`, `a*+`, `a?+`, `a{2,3}+`) were never consumed** -- the
   post-quantifier suffix check tested for a trailing `'*'` instead of `'+'` (`'*'` is never a valid
   quantifier-suffix character at all), leaving a stray literal `+` in the pattern. Fixed to check
   `'+'`.
8. **`Matcher#group(String)`/`start(String)`/`end(String)` resolved the wrong index for a pattern's
   *first* named group.** `pattern.namedGroups` stores the 0-based `captureConstructIndex`, but
   `group(int)` etc. expect 1-based public numbering (where `0` means "the whole match") --
   `groupIndexByName` returned the raw 0-based value unconverted, so the first named group in any
   pattern silently resolved to `group(0)` (the whole match) instead of its own text. Masked in the
   one prior test that happened to use a pattern where the whole match equals the group's own text.
   Fixed by adding 1.
9. **A non-quantified capturing group whose *content* contains its own internal loop** (e.g.
   `"([a-z]+)!"`) **never finalized its capture.** `PatternConstruct.CaptureEndMarker.buildEntryMap`
   aliased `entryMap = realNext.entryMap` directly -- but that leaves every entry's *value* as
   `realNext` itself, not the marker, breaking `LoopDispatchMatcherConstruct`'s `e.getValue() ==
   next` identity check (used to tell "the loop is exiting toward `next`" from "the loop is
   continuing"). The exit character got misclassified as a loop continuation and dispatched
   straight to `realNext.matcher`, bypassing the marker's own `EndCaptureMatcherConstruct` --
   so the capture's `Group` was created on entry but its `result` never set (`group(n)` returned
   `null` even though the whole pattern matched). Fixed by re-keying every range onto the marker
   itself, same as any other `PatternConstruct`'s own `buildEntryMap`.

All nine are covered by regression tests in the new test classes listed under "Testing" below.
Both scraped-corpus golden files were regenerated (`AGREES` 148+228=376, up from 142+221=363).

**Confirmed gap, not fixed** (real, but not a small/mechanical fix): `\p{Digit}` (the bare POSIX
form) throws "unknown named character class" instead of matching ASCII digits.
`NamedCharClass.java` has a POSIX `Digit` entry *commented out* (`// Digit(d.ascii, Digit),`)
because the name `Digit` is already taken by the `Source.UProperty` entry a few lines above (Java
enum constants can't share a name), and that UProperty entry only allows the `Is`-prefixed access
form. So there really are only **12** usable POSIX classes today, not the 13 Oracle documents
(`Lower`, `Upper`, `ASCII`, `Alpha`, `Alnum`, `Punct`, `Graph`, `Print`, `Blank`, `Cntrl`, `XDigit`,
`Space` -- no bare `Digit`). Needs a real design decision (e.g. renaming one of the two conflicting
entries) before fixing; tracked as its own item below. Covered by
`PosixAndJavaClassTest#posix_digit_notActuallyImplemented_throwsInstead`, which asserts the current
(unfortunate) behavior per this file's own instructions for a real-but-untriaged gap.

Also confirmed (not new, already known): Unicode scripts (`\p{IsScript}`/`\p{script=Script}`) and
blocks (`\p{InBlock}`/`\p{block=Block}`) are not implemented at all -- `NamedCharClass.java` has no
enum entry using `Source.Script` or `Source.Block`, so any such reference throws "unknown named
character class". Covered (as throwing) by `UnicodeClassTest`.

## HIGHEST PRIORITY

- [ ] **Give `\p{Digit}` (bare POSIX form) its own working entry** -- see the "confirmed gap" note
      just above. Needs a decision on how to avoid the enum-constant name collision with the
      existing `Source.UProperty` `Digit` (e.g. rename one of them, or make one enum entry support
      more than one `Source`/prefix set) before implementing.
- [ ] Implement Unicode scripts (`\p{IsScript}`/`\p{script=Script}`) and blocks (`\p{InBlock}`/
      `\p{block=Block}`) -- currently no `NamedCharClass` entries use `Source.Script`/`Source.Block`
      at all, so every such reference throws. Covered (as throwing) by `UnicodeClassTest`.

## Also remember for later (currently-unimplemented/deferred features)

- [ ] Once implemented, add the same depth of test coverage for: backreferences `\n` and
      `\k<name>` (see `BackReferenceMatcherConstruct`, currently a stub -- also flagged in
      design.md as "not actually context-free," may not fit the LL(1) model at all), quotation
      (`\Q...\E`), positive/negative lookahead (`(?=...)`/`(?!...)`), positive/negative lookbehind
      (`(?<=...)`/`(?<!...)`) -- note lookahead/lookbehind are currently rejected outright at
      parse time per design.md, and independent/atomic non-capturing groups (`(?>X)`).
- [ ] Of `java.util.regex.Pattern`'s remaining compile flags -- `CASE_INSENSITIVE`, `UNICODE_CASE`,
      and `DOTALL` are implemented (both globally and, as of 2026-09-06, correctly scoped through
      an inline `(?i:...)`/`(?s:...)` -- see the FIXED entries above); everything else is not
      started at all: `MULTILINE` (`^`/`$` currently only ever match start/end of input, never
      per-line -- `BoundaryConstruct`'s `LineBegin`/`LineEnd` matching is itself still a stub, see
      "HIGHEST PRIORITY" above), `UNIX_LINES` (which line terminators `MULTILINE`/`.` recognize --
      also noted as a `DOTALL`-fix follow-up above), `COMMENTS` (`(?x)` — whitespace/`#`-comment
      stripping in the pattern text; parses without error today per the inline-flag-toggle fix, but
      nothing in `PatternParser` actually acts on it), `LITERAL` (treat the whole pattern string as
      literal text, no metacharacters), and `CANON_EQ` (Unicode canonical-equivalence matching).
      None of these have any test coverage or even a stub `flags` branch, unlike the boundary/
      backreference stubs above -- they're simply unimplemented from scratch.

## Done

- [x] `CodePointMap`/`TreeCodePointMap`: interface fixed (consistent `[min, max)` convention, immutable `Range`, dropped broken/duplicate inline implementations), `TreeCodePointMap` rewritten against it and covered by 23 tests. `intersectionRejectingConflicts` added for the union-ambiguity-detection use case.
- [x] Fixed `UnicodePredicates.java`'s "code too large" compile error by changing the `unicodeanalyzer` generator to emit each field's builder chain as its own private static method (keeping the shared `<clinit>` small) and regenerating the file.
- [x] Toolchain: identified that Gradle 8.7 doesn't run reliably on this machine's default JDK 25 — use JDK 17/21 via `JAVA_HOME`. Checker Framework plugin disabled (incompatible with JDK 25) pending a version bump.
- [x] **AST → `MatcherConstruct` compilation, for literals/character classes/sequences/alternation**: working and tested, including real ambiguity detection.
- [x] **Quantifier/loop compilation** (`?`, `*`, `+`, `{n,m}`): `LoopDispatchMatcherConstruct`/`LoopMatcherConstruct`/`EndLoopMatcherConstruct` implemented and tested — see design.md's "compile() algorithm" section. This is also the first construct that actually exercises the self-registering-constructor cycle-handling mechanism (a loop body's "next" is the loop construct itself).
- [x] **Capturing groups** (`(a)`, `(a|b)`, `(?:...)`, and capturing-and-quantified at once like `(a)*`/`(a|b)+`): `BeginCaptureMatcherConstruct`/`EndCaptureMatcherConstruct`/`CaptureEndMarker` implemented and tested, including capture-inside-alternation, capture-in-the-middle-of-a-sequence, and "last iteration wins"/unset-on-zero-iterations semantics for the quantified case.
- [x] Found and fixed **seven** real, previously-latent bugs while building tests against the above (all pre-existing, not introduced by this work) — see the [2947d91](../.)/[2728911](../.) commit messages for full detail: `advanceCodePoint()` double-advancing; `parseComplexCharacter()` never consuming `]`; raw-text accumulation appending the wrong character; `{n,m}` overwriting `min` instead of setting `max`; a bare `?` never getting a counter slot; the top-level pattern misread as capturing group 0; `Matcher#consume1CodePoint`/`consumeCodeUnits`/`peek` crashing at end-of-input (plus a wrong surrogate-width check). Also one real bug in code written *this* session and caught by its own tests: `LiteralMatcherConstruct` dispatched post-consumption using a map keyed by its own first character (a mismatch) instead of an unconditional forward.
- [x] `PatternParserTest` (8 tests) and `QuantifierAndCaptureTest` (21 tests) — see Testing section.
- [x] **`Ll1Pattern`/`Matcher` public API**: `matcher(CharSequence)`, `matches()`, `lookingAt()`, `find()`/`find(int)`, `group()`/`group(int)`/`group(String)`, `start()`/`start(int)`/`start(String)`, `end()`/`end(int)`/`end(String)`, `groupCount()`, `region(int,int)`, `reset()`/`reset(String)`. `Matcher#quantifiableCounts`/`captureGroups` are now sized automatically from the compiled pattern (`PatternParser` exposes final counts + a name→index map after `parse()`). See design.md for how `matches()` and `lookingAt()`/`find()` share one compiled graph via a runtime flag rather than needing separate compilations.
- [x] **Two real, previously-latent `NamedCharClass` bugs, found and fixed 2026-09-06** while
      building the scraped-corpus harness (below) -- `NamedCharClass` had zero prior test coverage
      (nothing in the repo used `\w`/`\d`/`\s`/`\p{...}`/POSIX classes before this session), so
      its static initializer had simply never run:
      1. 19 spurious empty ranges (`Range.closedOpen(0x110000, 0x110000)`) in generated
         `UnicodePredicates.java`, crashing `ImmutableRangeSet.Builder.build()`. Fixed by deleting
         the 19 no-op lines (mechanical, verified safe: full existing suite stayed green).
         Root cause in the `unicodeanalyzer` generator not yet investigated -- if it's
         regenerated, check whether this recurs.
      2. Three spots in `NamedCharClass.java` (`Blank`'s unicode branch, `Print`'s unicode branch,
         `RegexCharacterClass.w`'s unicode branch) unioned multiple Unicode range sets via
         `ImmutableRangeSet.Builder().addAll(a).addAll(b)...`, which throws on any overlap between
         them -- and they do overlap (e.g. code point 837 is claimed by both `Alphabetic` and
         `NON_SPACING_MARK`). Fixed by adding `NamedCharClass.union(RangeSet<Integer>...)` (merges
         via a mutable `TreeRangeSet`, which coalesces instead of rejecting overlaps) and using it
         at all three sites instead of `Builder`.

## Scraped-corpus differential test harness (implemented 2026-09-06)

Design: `documents/tools/scrape_<source>.py` fetches a source project's own regex test data and
emits an "intermediate" TSV of `(pattern, flags, input[, mode])` tuples (no per-source
knowledge of expected results -- we compute those ourselves, see below). `CorpusGenerator`
(`llkpattern/src/test/java/.../corpus/CorpusGenerator.java`, run via
`./gradlew :llkpattern:generateCorpus -Pinput=... -Poutput=... -Pmode=... -Punescape=...`) reads
that, runs each tuple through both `java.util.regex` and `Ll1Pattern` (`MatchRunner`), and writes a
golden TSV (`GoldenRow`/`GoldenTsv`) with the recorded outcome of both plus an auto-tagged `status`
column (`AGREES`/`UNIMPLEMENTED: ...`/`UNEXPECTED: ...` -- see `CorpusGenerator`'s javadoc; these
are a first-pass heuristic, NOT a human-verified verdict). `ScrapedCorpusTestBase` is a JUnit4
`@Parameterized` base class; one concrete subclass per golden file (`OpenJdkBmpCorpusTest`,
`OpenJdkSupplementaryCorpusTest`) just supplies the file path. Per row, only `Ll1Pattern` is
re-run and compared against the golden `llk*` columns on every test invocation --
`java.util.regex`'s own re-verification (`regexMatchesGolden`) is implemented but `@Ignore`d by
default (per the project owner, 2026-09-06): its behavior is fixed JDK behavior this project can't
regress, so re-running it on every test is pure cost, and it reintroduces the pathological-input
risk below for no payoff. Re-enable it manually (comment out `@Ignore`, or run directly) to
double-check the `regex*` columns against whatever JDK is actually installed.

**Pathological-input handling**: some source suites deliberately test catastrophic-backtracking
patterns, which made bare generation hang forever the first time this ran. `GoldenRow` has a 12th
column, `originalPathologicalInput` (empty in the common case): `CorpusGenerator` probes each row
with a 100ms-timeout wall-clock budget (`MatchRunner.runWithTimeout`, a fresh daemon thread per
call -- deliberately NOT a shared thread, since a shared one stays permanently blocked after the
first genuine hang and would poison every later row); on timeout, it repeatedly halves the input
length until both engines finish within budget, records the shrunk input as `input` and the
original as `originalPathologicalInput`, and prefixes `status` with
`PATHOLOGICAL_INPUT_SIMPLIFIED (...)`. This is deliberately a generation-time-only concern (per
the project owner, 2026-09-06): since regular test runs don't re-run `java.util.regex` at all (see
above) and `Ll1Pattern` isn't expected to hang the way backtracking regex can, ongoing test runs
don't need the shrink machinery -- `llkMatchesGolden` still wraps its own re-run in a (generous,
2000ms) timeout purely as defense-in-depth, turning a future counterexample into a clean test
failure instead of a hung suite.

**Current results** (`llkpattern/src/test/resources/golden/openjdk_bmp.tsv`, 222 rows;
`openjdk_supplementary.tsv`, 339 rows; regenerate via the `generateCorpus` command above after any
scraping/unescaping/engine change):

- BMP: 148 AGREES (was 142), UNIMPLEMENTED/UNEXPECTED auto-tagged for the rest.
- Supplementary: 228 AGREES (was 221), UNIMPLEMENTED/UNEXPECTED auto-tagged for the rest.
- Regenerated again 2026-09-06 after the comprehensive-grammar-coverage bug fixes above (`\p{...}`
  prefix detection, `\P{...}` negation, `{n}`'s unconsumed `}`, possessive-quantifier suffix, etc.)
  moved several more rows to `AGREES`.
- The 4 rows that used to hang `llkMatchesGolden` outright no longer do -- see "FIXED (2026-09-06):
  the `PatternParser` hang..." above.
- Character-class intersection (`&&`) is fixed -- see "FIXED (2026-09-06): character-class
  intersection..." above; this alone moved ~86 rows from UNIMPLEMENTED/UNEXPECTED to AGREES across
  both files.
- The surrogate-pair matching bug and its two associated finds (unclamped negated-class ranges
  colliding with the end-of-input sentinel; `\p{...}` crashing as the pattern's last construct) are
  fixed -- see "FIXED (2026-09-06): llk matched into the low half of a *valid* surrogate pair"
  above; moved another ~19 rows to `AGREES`.
- `CASE_INSENSITIVE`/`UNICODE_CASE`/inline flag toggles/`DOTALL` are fixed -- see "FIXED
  (2026-09-06): CASE_INSENSITIVE/UNICODE_CASE, inline flag toggles, and DOTALL" above. Net effect on
  the corpus at that point was small and mixed (a handful of `.`-without-`DOTALL` rows moved to
  `AGREES`; roughly as many `(?iu)`/`(?x)`/`(?s)` rows moved from crashing to a clean, understood
  `UNEXPECTED` instead, because of the two follow-up bugs that fix surfaced).
- Both of those two follow-up bugs (bare `(?s)` breaking the surrounding sequence; inline flag
  toggles not actually locally scoping anything) are now also fixed -- see their own FIXED entries
  above. Together they moved 12 rows (6 per file -- the exact `(?s)`/`(?iu)`/`(?x)` rows the earlier
  fix had turned into a clean `UNEXPECTED`) from `UNEXPECTED` to `AGREES`. Regenerated by running
  `CorpusGenerator.generateRow()` against each golden file's own existing
  `(pattern, flags, input, mode)` columns (a temporary `RegenerateGoldenFromSelf` class + matching
  `regenerateGoldenFromSelf` gradle task, both deleted after use) rather than the real
  `generateCorpus` intermediate-TSV path, since no original OpenJDK-scrape intermediate TSV is kept
  in the repo -- fine here since nothing about scraping/unescaping changed, only `Ll1Pattern`'s own
  behavior. AGREES is now 142+221=363 total, up from 65+136=201 at the start of this session.
- The remaining `UNIMPLEMENTED`/`UNEXPECTED` counts are **not yet human-reviewed** -- per
  `CorpusGenerator`'s javadoc, "UNIMPLEMENTED" really just means "llk didn't run cleanly" (could
  be a correct LL(1)-ambiguity rejection, not a missing feature) and needs retagging as
  `EXPECTED_DIVERGENCE` where that's the case.

**Next steps** (not yet started):

- [ ] Human triage pass over every non-`AGREES` row in both golden files -- retag
      `EXPECTED_DIVERGENCE` vs leave as a real bug to fix, per `CorpusGenerator`'s status scheme.
- [ ] More sources, each as its own `scrape_<source>.py` + golden file + `ScrapedCorpusTestBase`
      subclass (the pipeline already supports this cleanly):
  - [ ] **AOSP/libcore**: `https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/test/java/util/regex/`
        (per the project owner, 2026-09-06 -- note this is `libcore`, not
        `platform_frameworks_base` as an earlier draft of this file guessed).
  - [ ] **RE2J**: `https://github.com/google/re2j/tree/master/javatests/com/google/re2j` (per the
        project owner, 2026-09-06) -- interesting as a comparison point since RE2J, like llk, is a
        deliberately linear-time (non-backtracking) engine, just via a different mechanism (Thompson
        NFA simulation vs LL(1) compile-time dispatch).
  - [ ] **dregex**: `https://github.com/marianobarrios/dregex/tree/master/src/test/java/dregex`
        (per the project owner, 2026-09-06).
  - [ ] Oracle GraalVM's regex engine tests were the third original candidate (see the superseded
        entry this section replaces) -- not yet located/confirmed.
  - [ ] **dk.brics.automaton**: `https://github.com/cs-au-dk/dk.brics.automaton/tree/master/test/java/dk/brics/automaton`
        (per the project owner, 2026-09-06).
  - [ ] **DataDog/java-reggie**: `https://github.com/DataDog/java-reggie/tree/main/reggie-integration-tests/src/test/java/com/datadoghq/reggie/integration`
        (per the project owner, 2026-09-06).
- [ ] **Investigate java-reggie's `FuzzTest`** (`https://github.com/DataDog/java-reggie` --
      look under its integration-tests module, per the project owner, 2026-09-06) to see how it
      picks fuzzed inputs and decides pass/fail. This project considered a fuzz test for
      `Ll1Pattern` before and shelved it for exactly that reason: it wasn't clear which inputs to
      generate for an arbitrary pattern, or what the "correct" outcome even is without an oracle to
      compare against. java-reggie apparently found an answer worth copying (possibly the same
      differential-against-`java.util.regex` idea this project's scraped-corpus harness already
      uses, possibly something else, e.g. pattern-directed input generation) -- read it before
      building anything, don't just copy the "fuzz" label.

## Scraped-corpus microbenchmark (idea, 2026-09-06)

- [ ] **Add a microbenchmark that compares `java.util.regex` vs `Ll1Pattern` speed over the
      scraped-corpus golden files** (per the project owner, 2026-09-06). Sketch: load every golden
      row from `openjdk_bmp.tsv`/`openjdk_supplementary.tsv` (and any later-added corpus files, see
      above), keep only rows where `status == "AGREES"` (both engines compile and produce the same
      match outcome -- comparing speed on a row where the engines disagree about *correctness*
      isn't meaningful), then time (a) `java.util.regex.Pattern.compile(...)` + the matching call
      (`matches`/`lookingAt`/`find`, per the row's `mode`) and (b) `Ll1Pattern`'s equivalent, and
      report both. Open questions to settle before building:
  - JMH vs a hand-rolled JUnit timer loop -- JMH is the right tool for real microbenchmark rigor
        (warmup iterations, fork isolation, avoiding dead-code elimination) but is a new build
        dependency/plugin; a fake-it-with-JUnit version (loop N times, discard a warmup prefix,
        report min/median/mean) is far less rigorous but zero new dependencies. Ask the project
        owner which tradeoff they want once this is picked up.
  - Compile time and match time probably need reporting separately (llk likely compiles slower --
        it's building a full dispatch graph upfront -- but may match faster per-call; a combined
        number would hide that story).
  - Needs a decision on where results go: console output only (simplest), a checked-in baseline
        file to diff against (catches regressions), or both.

## Core implementation

- [ ] **Performance: split `MatcherConstruct.dispatchMap` into two node shapes** (proposed by the project owner 2026-09-06, not yet implemented): observe that almost every `MatcherConstruct` (all but `EndLoopMatcherConstruct`, arguably `EndMatcherConstruct`) is actually followed by exactly *one* possible next node — the current `RangeMap`-based `dispatchMap` is overkill for those and blocks JIT inlining. Proposed shape: move today's `dispatchMap` down into a `MultiDispatchingMatcherConstruct` (extended by whatever genuinely branches, e.g. `EndLoopMatcherConstruct`/loop-dispatch/union-dispatch nodes), and add a `SingleDispatchingMatcherConstruct` abstract class with `final MatcherConstruct next` (+ eventually `final MethodHandle nextMethod`, revisiting the MethodHandle idea design.md marked moot under the old all-nodes-have-a-RangeMap design) for every node that only ever has one successor — a branching node's "successor" for those still becomes a `MultiDispatchingMatcherConstruct`, so nothing loses the ability to branch, only nodes that never needed to pay for it stop paying for it. Should reduce both time and memory for the common (non-branching) case. This is a real architectural change touching most of `MatcherConstruct.java`; do it as its own focused pass, not opportunistically alongside other work.
- [ ] Implement `MatcherConstruct.BackReferenceMatcherConstruct.match(...)` (currently throws; the node itself is now correctly wired into the graph, just the runtime behavior is missing).
- [ ] Implement `MatcherConstruct.BoundaryMatcherConstruct.match(...)` (currently throws; same as above — structurally present, behaviorally stubbed). This needs matcher *state* (position, surrounding characters), not just the next code point, so it may need a different mechanism than a plain dispatch map — see design.md.
- [ ] Remaining `Matcher`/`Ll1Pattern` API gaps: `replaceAll`/`replaceFirst`/`appendReplacement`/`appendTail`/`quoteReplacement`, `split`/`splitAsStream`, `toMatchResult`, `hitEnd`/`requireEnd`, `useAnchoringBounds`/`hasAnchoringBounds`, `useTransparentBounds`/`hasTransparentBounds` — all still `UnsupportedOperationException` stubs. None of these are needed for the scraped-corpus differential test harness below (that only needs `matches`/`find`/`group`/`start`/`end`), so lower priority than that.
- [ ] `region()`'s interaction with `hasAnchoringBounds`/`useAnchoringBounds`/`useTransparentBounds` (whether `^`/`$`/boundaries see past the region) isn't implemented at all yet — moot until `BoundaryMatcherConstruct` itself works, but worth remembering once it does.
- [ ] `PatternConstruct.compile()` is typed `@Nullable MatcherConstruct` but, now that every construct type actually builds a matcher, likely always returns non-null in practice — worth dropping the `@Nullable` (and fixing `Ll1Pattern.compile()`'s unchecked-nullable assignment).
- [ ] `PatternSyntaxException.Reference` is constructed in a couple of places (e.g. the old, since-rewritten ambiguity-detection attempt) but was never actually handled in `PatternSyntaxException.throwWithReferences` — it silently falls through to `Object.toString()` (`Reference@<hashcode>`). Either implement it (render the referenced snippet, as `CodePoint`/`CodePointReference` do) or remove it if `CodePoint`-based messages turn out to be sufficient. Current loop/union ambiguity messages avoid it, using plain indices/`CodePoint` instead.
- [ ] Migrate `ComplexCharacter`'s direct Guava `RangeSet<Integer>` usage onto `CodePointMap`, or decide it should stay separate (`ComplexCharacter` represents a single character class's ranges, which is a slightly different job than `CodePointMap`'s "ranges to values"; worth a deliberate decision rather than reflexive migration).
- [ ] Eventually replace `TreeCodePointMap`'s Guava `TreeRangeMap` delegation with a more specialized/optimized code-point range structure — explicitly called out by the project owner as a later step, not needed for a first working version. The `CodePointMap` interface exists specifically so this swap doesn't require touching callers. Design sketch from the project owner (2026-09-06):
  - Two parallel arrays: `int[] codePointKeys` and `V[] values`. Each `codePointKeys` entry is a
    bitfield -- high 21 bits the range's min code point, low 11 bits the count of *additional*
    code points after `min` that map to the same value (i.e. the range is `[min, min+count]`).
    `codePointKeys` is sorted as if unsigned. Lookup: binary- or linear-search for the last key
    `<= codePoint`, subtract that key's `min` from the input code point, and check the result is
    `<= count` (i.e. within the range) -- on a hit, return the `values` entry at the same index.
  - Immutable map type plus a separate mutable builder, same shape as `TreeCodePointMap`'s
    existing immutable/builder split.
  - Expected win: dramatically less memory than `TreeRangeMap`-backed `TreeCodePointMap`
    (no per-entry object/node overhead, two flat arrays), which should also mean better CPU-cache
    behavior during matching.
  - **Followup experiment** (once the above works): shrink the range field to 10 bits and use the
    freed 11th bit as a mask-vs-range flag. When set, the 10 "range" bits are instead a bitmask of
    which of the 10 code points *after* `min` also map to this value (not required to be
    contiguous) -- lookup then has to branch on the flag and, on a mask hit, may need to check up
    to 11 candidate keys in a row (since a mask entry no longer implies contiguous coverage the
    way a range entry does), so it trades lookup speed for density. Good fit for
    alternating-but-not-contiguous data (e.g. `isLowerCase` over `0x100`-`0x137`, which alternates
    upper/lower every code point and would otherwise need one range entry per code point). Also
    worth trying a `long[]` variant with 42 range/mask bits instead of `int[]`'s 11, trading larger
    per-entry size for fewer wasted bits when ranges/gaps are long.
- [ ] `CodePointMap.ComplementCodePointMap` is only partially implemented (`entrySet`/`intersection`/`intersectionRejectingConflicts` throw `UnsupportedOperationException`) — fill in once there's a concrete caller/use case driving what's actually needed (`.` in a branching context is the likely first caller).
- [ ] The `PatternConstruct.findFirstOverlap` ambiguity check is an O(candidates × ranges) manual scan rather than using `CodePointMap.intersectionRejectingConflicts` directly, because the latter's exception doesn't carry which range/candidate conflicted. Fine for realistic pattern sizes; revisit only if it matters in practice.

## Toolchain

- [ ] Pin a Checker Framework version compatible with modern JDKs (or a JDK toolchain constraint) and re-enable the nullness checker in `llkpattern/build.gradle` — currently disabled because the default-resolved 3.19.0 crashes against JDK 25's javac internals.
- [ ] Consider bumping the Gradle wrapper (currently 8.7) so it can run on newer JDKs directly, instead of requiring `JAVA_HOME` to point at JDK 17/21. Check compatibility with the Android Gradle Plugin used by `app/` first.
- [ ] Add a documented/scripted way to run `unicodeanalyzer` and regenerate `UnicodePredicates.java`, rather than the current copy-paste-and-hand-assemble process used to fix the "code too large" bug.

## Testing

- [x] `TreeCodePointMapTest` — 23 tests covering the range convention, core map operations, union/difference/intersection, conflict detection, `compute`/`computeIfAbsent`, `complement`, `equals`, and the copy constructor.
- [x] `PatternParserTest` — 8 tests covering literal/multi-char-literal/sequence/alternation compilation, ambiguous-alternation rejection, and real `match()`-level assertions (not just dispatchMap structure — the latter alone missed the `LiteralMatcherConstruct` bug noted above).
- [x] `QuantifierAndCaptureTest` — 21 tests covering `*`/`+`/`?`/`{n,m}` (including the exact regression case for the `{n,m}` min/max-swap bug), alternation/capturing/named-groups combinations, and the capturing-and-quantified case.
- [x] `MatcherApiTest` — 18 tests covering `matches()` vs `lookingAt()`, `find()` (locating a non-prefix match, repeated calls advancing past the previous match, explicit start index, empty-match forward progress), regions, `reset()`, `groupCount()`, the three group-accessor exception cases, unmatched-optional-group null/-1, `asPredicate()`, and the static `matches()` helper.
- [x] **Comprehensive grammar coverage** (2026-09-06): `EscapeTest` (17 tests: `\\`, octal, hex/unicode, `\t`/`\n`/`\r`/`\f`/`\a`/`\e`, `\cX`), `CharacterClassTest` (7: sets, negation, ranges, unions, intersections incl. negated-operand vs negated-whole-intersection), `PredefinedClassTest` (12: `.`, `\d`/`\D`, `\h`/`\H`, `\s`/`\S`, `\v`/`\V`, `\w`/`\W`, `\R`), `PosixAndJavaClassTest` (33: all 13 documented POSIX names -- 12 working + `Digit`'s tracked gap -- plus every `Source.Java` entry), `UnicodeClassTest` (18: categories, binary properties, `\P{...}` negation, Unicode names inside a class, scripts/blocks' confirmed non-implementation), `QuantifierModifierTest` (13: `{n}`/`{n,}` plus reluctant/possessive on every quantifier kind), `GroupSyntaxTest` (7: named/non-capturing groups). Found and fixed nine real bugs along the way -- see the "FIXED" entry above. `CaseInsensitiveTest` already covered inline flag toggles.
- [ ] Add more parser tests covering boundaries and backreferences syntax once those are implemented — see "Also remember for later" below.
- [ ] Cross-check current `Matcher` behavior against `java.util.regex.Pattern`/`Matcher` for the subset of syntax both support — not yet done beyond what the tests above assert from first principles; see the scraped-corpus harness idea below for the systematic version of this.
- [ ] Decide on a CI setup (or at least a documented local command, given the JDK version constraint above) to run the suite "frequently" per the owner's stated preference.
- [x] **Scraped-corpus differential test harness**: implemented 2026-09-06 for OpenJDK's own
      `java.util.regex` test data (`test/jdk/java/util/regex/BMPTestCases.txt` and
      `SupplementaryTestCases.txt`) -- see the "Scraped-corpus differential test harness" section
      below for the full design, current results, and what's next (more sources: AOSP/libcore,
      RE2J, dregex).

## Housekeeping / cleanup

- [ ] Clarify the relationship between `llkpattern/` (current), `oldllkpattern/` (prior version, kept for reference) — is `oldllkpattern` still needed, or can it be removed/archived once the new implementation catches up?
- [ ] Clarify what the `app/` Gradle module (looks like default Android app boilerplate) is for in this project — is it a demo/harness, or leftover scaffolding from `File > New Project` that can be deleted?
- [ ] Fill in section 2 (High-Level Design) and section 3 (Current Progress) of [README.md](../README.md) in more depth as the design solidifies (updated 2026-09-06; still not a full design writeup in the README itself, which continues to point at design.md).
