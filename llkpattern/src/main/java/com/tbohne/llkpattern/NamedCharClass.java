package com.tbohne.llkpattern;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.tbohne.llkpattern.CodePointMap.MutableCodePointMap;

import java.util.regex.Pattern;

enum NamedCharClass {
  // Java Character methods
  javaValidCodePoint(
      Source.Java, UnicodePredicates.isValidCodePoint),
  javaBmpCodePoint(
      Source.Java, UnicodePredicates.isBmpCodePoint),
  javaSupplementaryCodePoint(
      Source.Java, UnicodePredicates.isSupplementaryCodePoint),
  javaLowerCase(Source.Java, UnicodePredicates.isLowerCase),
  javaUpperCase(Source.Java, UnicodePredicates.isUpperCase),
  javaTitleCase(Source.Java, UnicodePredicates.isTitleCase),
  javaDigit(Source.Java, UnicodePredicates.isDigit),
  javaDefined(Source.Java, UnicodePredicates.isDefined),
  javaLetter(Source.Java, UnicodePredicates.isLetter),
  javaLetterOrDigit(
      Source.Java, UnicodePredicates.isLetterOrDigit),
  javaAlphabetic(Source.Java, UnicodePredicates.isAlphabetic),
  javaIdeographic(Source.Java, UnicodePredicates.isIdeographic),
  javaJavaIdentifierStart(
      Source.Java, UnicodePredicates.isJavaIdentifierStart),
  javaJavaIdentifierPart(
      Source.Java, UnicodePredicates.isJavaIdentifierPart),
  javaUnicodeIdentifierStart(
      Source.Java, UnicodePredicates.isUnicodeIdentifierStart),
  javaUnicodeIdentifierPart(
      Source.Java, UnicodePredicates.isUnicodeIdentifierPart),
  javaIdentifierIgnorable(
      Source.Java, UnicodePredicates.isIdentifierIgnorable),
  javaSpaceChar(Source.Java, UnicodePredicates.isSpaceChar),
  javaWhitespace(Source.Java, UnicodePredicates.isWhitespace),
  javaISOControl(Source.Java, UnicodePredicates.isISOControl),
  javaMirrored(Source.Java, UnicodePredicates.isMirrored),

  // Unicode Categories https://www.unicode.org/reports/tr44/#GC_Values_Table
  Lu(Source.Category, UnicodePredicates.UPPERCASE_LETTER),
  Ll(Source.Category, UnicodePredicates.LOWERCASE_LETTER),
  Lt(Source.Category, UnicodePredicates.TITLECASE_LETTER),
  LC(
      Source.Category,
      unionOf(UnicodePredicates.UPPERCASE_LETTER, UnicodePredicates.LOWERCASE_LETTER,
              UnicodePredicates.TITLECASE_LETTER)),
  Lm(Source.Category, UnicodePredicates.MODIFIER_LETTER),
  Lo(Source.Category, UnicodePredicates.OTHER_LETTER),
  L(
      Source.Category,
      unionOf(UnicodePredicates.UPPERCASE_LETTER, UnicodePredicates.LOWERCASE_LETTER,
              UnicodePredicates.TITLECASE_LETTER, UnicodePredicates.MODIFIER_LETTER, UnicodePredicates.OTHER_LETTER)),
  Mn(Source.Category, UnicodePredicates.NON_SPACING_MARK),
  Mc(
      Source.Category,
      UnicodePredicates.COMBINING_SPACING_MARK),
  Me(Source.Category, UnicodePredicates.ENCLOSING_MARK),
  M(
      Source.Category,
      unionOf(UnicodePredicates.NON_SPACING_MARK, UnicodePredicates.COMBINING_SPACING_MARK,
              UnicodePredicates.ENCLOSING_MARK)),
  Nd(
      Source.Category,
      UnicodePredicates.DECIMAL_DIGIT_NUMBER),
  Nl(Source.Category, UnicodePredicates.LETTER_NUMBER),
  No(Source.Category, UnicodePredicates.OTHER_NUMBER),
  N(
      Source.Category,
      unionOf(UnicodePredicates.DECIMAL_DIGIT_NUMBER, UnicodePredicates.LETTER_NUMBER, UnicodePredicates.OTHER_NUMBER)),
  Pc(
      Source.Category,
      UnicodePredicates.CONNECTOR_PUNCTUATION),
  Pd(Source.Category, UnicodePredicates.DASH_PUNCTUATION),
  Ps(Source.Category, UnicodePredicates.START_PUNCTUATION),
  Pe(Source.Category, UnicodePredicates.END_PUNCTUATION),
  Pi(
      Source.Category,
      UnicodePredicates.INITIAL_QUOTE_PUNCTUATION),
  Pf(
      Source.Category,
      UnicodePredicates.FINAL_QUOTE_PUNCTUATION),
  Po(Source.Category, UnicodePredicates.OTHER_PUNCTUATION),
  P(
      Source.Category,
      unionOf(UnicodePredicates.CONNECTOR_PUNCTUATION, UnicodePredicates.DASH_PUNCTUATION,
              UnicodePredicates.START_PUNCTUATION, UnicodePredicates.END_PUNCTUATION,
              UnicodePredicates.INITIAL_QUOTE_PUNCTUATION, UnicodePredicates.FINAL_QUOTE_PUNCTUATION,
              UnicodePredicates.OTHER_PUNCTUATION)),
  Sm(Source.Category, UnicodePredicates.MATH_SYMBOL),
  Sc(Source.Category, UnicodePredicates.CURRENCY_SYMBOL),
  Sk(Source.Category, UnicodePredicates.MODIFIER_SYMBOL),
  So(Source.Category, UnicodePredicates.OTHER_SYMBOL),
  S(
      Source.Category,
      unionOf(UnicodePredicates.MATH_SYMBOL, UnicodePredicates.CURRENCY_SYMBOL, UnicodePredicates.MODIFIER_SYMBOL,
              UnicodePredicates.OTHER_SYMBOL)),
  Zs(Source.Category, UnicodePredicates.SPACE_SEPARATOR),
  Zl(Source.Category, UnicodePredicates.LINE_SEPARATOR),
  Zp(
      Source.Category,
      UnicodePredicates.PARAGRAPH_SEPARATOR),
  Z(
      Source.Category,
      unionOf(UnicodePredicates.SPACE_SEPARATOR, UnicodePredicates.LINE_SEPARATOR,
              UnicodePredicates.PARAGRAPH_SEPARATOR)),
  Cc(Source.Category, UnicodePredicates.CONTROL),
  Cf(Source.Category, UnicodePredicates.FORMAT),
  Cs(Source.Category, UnicodePredicates.SURROGATE),
  Co(Source.Category, UnicodePredicates.PRIVATE_USE),
  Cn(Source.Category, UnicodePredicates.UNASSIGNED),
  C(
      Source.Category,
      unionOf(UnicodePredicates.CONTROL, UnicodePredicates.FORMAT, UnicodePredicates.SURROGATE,
              UnicodePredicates.PRIVATE_USE, UnicodePredicates.UNASSIGNED)),

  // Unicode Binary Properties
  Alphabetic(Source.UProperty, javaAlphabetic),
  Ideographic(Source.UProperty, javaIdeographic),
  Letter(Source.UProperty, javaLetter),
  Lowercase(Source.UProperty, javaLowerCase),
  Uppercase(Source.UProperty, javaUpperCase),
  Titlecase(Source.UProperty, javaTitleCase),
  Punctuation(
      Source.UProperty,
      unionOf(UnicodePredicates.CONNECTOR_PUNCTUATION, UnicodePredicates.DASH_PUNCTUATION,
              UnicodePredicates.START_PUNCTUATION, UnicodePredicates.END_PUNCTUATION,
              UnicodePredicates.INITIAL_QUOTE_PUNCTUATION, UnicodePredicates.FINAL_QUOTE_PUNCTUATION,
              UnicodePredicates.OTHER_PUNCTUATION)),
  Control(Source.UProperty, javaISOControl),
  // Bug fix (2026-09-07): this used to delegate to javaWhitespace (Character.isWhitespace), but
  // the real Unicode White_Space binary property and Character.isWhitespace() are NOT the same
  // set -- Character.isWhitespace()'s own javadoc deliberately excludes NO-BREAK SPACE (U+00A0),
  // NARROW NO-BREAK SPACE (U+202F), and MEDIUM MATHEMATICAL SPACE (U+205F) as "non-breaking",
  // while the Unicode property includes them. Found via adding systematic ASCII-vs-Unicode test
  // coverage for \p{Space}/\p{Blank} (both widen off this set under UNICODE_CHARACTER_CLASS) --
  // verified against real java.util.regex (including that U+180E, sometimes assumed to be
  // whitespace, is correctly excluded -- it was removed from the Unicode White_Space property in
  // Unicode 6.3) before fixing. Hand-built rather than reused from RegexCharacterClass's `h`/`v`
  // (whose union is the same set, modulo `h`'s own inclusion of U+180E) to avoid the
  // NamedCharClass<->RegexCharacterClass circular static-init dependency documented on Space
  // below -- this is the same literal-duplication tradeoff Space's own ASCII set already makes.
  White_Space(
      Source.UProperty,
      build(
          m -> {
            m.put(+'\t', +'\r' + 1, Boolean.TRUE); // U+0009-000D
            m.put(+' ', Boolean.TRUE);
            m.put(0x0085, Boolean.TRUE);
            m.put(0x00A0, Boolean.TRUE);
            m.put(0x1680, Boolean.TRUE);
            m.put(0x2000, 0x200B, Boolean.TRUE);
            m.put(0x2028, Boolean.TRUE);
            m.put(0x2029, Boolean.TRUE);
            m.put(0x202F, Boolean.TRUE);
            m.put(0x205F, Boolean.TRUE);
            m.put(0x3000, Boolean.TRUE);
          })),
  // Digit is reachable both as the bare POSIX class \p{Digit} (ASCII-default, widens to
  // full-Unicode only under UNICODE_CHARACTER_CLASS) and as the Unicode binary property
  // \p{IsDigit} (always full-Unicode, the flag never applies) -- the only one of the 13 POSIX
  // class names that also happens to be spelled identically to a Unicode binary property name
  // (every other POSIX/UProperty pair differs, e.g. Alpha/Alphabetic, Space/White_Space, so no
  // other POSIX class needs this). Verified against real java.util.regex: bare \p{Alphabetic} and
  // \p{White_Space} both throw "Unknown character property name", confirming this dual-prefix
  // reachability really is unique to Digit, not a rule that should apply more broadly. Needs the
  // explicit prefix-set override below since neither Source.POSIX (none only) nor Source.UProperty
  // (is only) permits both by itself; get()'s prefix check (see below) handles making the `is`
  // half always-full-Unicode regardless of flags.
  Digit(
      ImmutableSet.of(CharacterClassPrefix.none, CharacterClassPrefix.is),
      Source.UProperty, javaDigit.unicode, /* slicedAscii=*/true),
  // Bug fix (2026-09-06): this used to range over the FULL a-z/A-Z alphabet (and the fullwidth
  // equivalent of the full alphabet), matching every letter as a "hex digit" instead of just
  // a-f/A-F. Found via PosixAndJavaClassTest's \p{XDigit} coverage ("g" wrongly matched). See
  // remaining_work.md.
  Hex_Digit(
      Source.UProperty,
      build(
          m -> {
            m.put(+'a', +'f' + 1, Boolean.TRUE);
            m.put(+'A', +'F' + 1, Boolean.TRUE);
            m.put(+'0', +'9' + 1, Boolean.TRUE);
            m.put(0xFF41, 0xFF47, Boolean.TRUE);
            m.put(0xFF21, 0xFF27, Boolean.TRUE);
            m.put(0xFF10, 0xFF1A, Boolean.TRUE);
          })),
  Join_Control(
      Source.UProperty, build(m -> m.put(0x200C, 0x200E, Boolean.TRUE))),
  Noncharacter_Code_Point(
      Source.UProperty,
      build( // not public in Java :(
          m -> {
            m.put(0xFDD0, 0xFDF0, Boolean.TRUE);
            m.put(0xFFFE, 0x10000, Boolean.TRUE);
            m.put(0x1FFFE, 0x20000, Boolean.TRUE);
            m.put(0x2FFFE, 0x30000, Boolean.TRUE);
            m.put(0x3FFFE, 0x40000, Boolean.TRUE);
            m.put(0x4FFFE, 0x50000, Boolean.TRUE);
            m.put(0x5FFFE, 0x60000, Boolean.TRUE);
            m.put(0x6FFFE, 0x70000, Boolean.TRUE);
            m.put(0x7FFFE, 0x80000, Boolean.TRUE);
            m.put(0x8FFFE, 0x90000, Boolean.TRUE);
            m.put(0x9FFFE, 0xA0000, Boolean.TRUE);
            m.put(0xAFFFE, 0xB0000, Boolean.TRUE);
            m.put(0xBFFFE, 0xC0000, Boolean.TRUE);
            m.put(0xCFFFE, 0xD0000, Boolean.TRUE);
            m.put(0xDFFFE, 0xE0000, Boolean.TRUE);
            m.put(0xEFFFE, 0xF0000, Boolean.TRUE);
            m.put(0xFFFFE, 0x100000, Boolean.TRUE);
            m.put(0x10FFFE, 0x110000, Boolean.TRUE);
          })),
  Assigned(
      Source.UProperty,
      UnicodePredicates.UNASSIGNED.complement(Boolean.TRUE)),

  // POSIX character classes
  Lower(
      Source.POSIX,
      Lowercase.unicode,
      /* slicedAscii=*/true),
  Upper(
      Source.POSIX,
      Uppercase.unicode, /* slicedAscii=*/true),
  ASCII(
      Source.POSIX,
      UnicodePredicates.ascii),
  Alpha(
      Source.POSIX,
      Alphabetic.unicode, /* slicedAscii=*/true),
  // Bug fix (2026-09-07): this used to be a single-RangeSet constructor call (ascii == unicode),
  // so \p{Alnum} always matched the full-Unicode alphanumeric set, ignoring
  // UNICODE_CHARACTER_CLASS entirely -- found via adding systematic ASCII-vs-Unicode coverage for
  // every POSIX/java class (see PosixAndJavaClassTest), verified against real java.util.regex.
  // slicedAscii's intersection with UnicodePredicates.ascii correctly reduces this union down to
  // plain ASCII [0-9A-Za-z], matching POSIX Alnum's real default behavior.
  Alnum(
      Source.POSIX,
      unionOf(Alphabetic.unicode, Digit.unicode), /* slicedAscii=*/true),
  Punct(
      Source.POSIX,
      build(
          m -> {
            m.put(0x0021, 0x0030, Boolean.TRUE);
            m.put(0x003a, 0x0041, Boolean.TRUE);
            m.put(0x005B, 0x0061, Boolean.TRUE);
            m.put(0x007B, 0x007F, Boolean.TRUE);
          }),
      Punctuation.unicode),
  Graph(
      Source.POSIX,
      unionOf(Alnum.ascii, Punct.ascii),
      unionOf(UnicodePredicates.isWhitespace,
              UnicodePredicates.CONTROL,
              UnicodePredicates.SURROGATE,
              UnicodePredicates.UNASSIGNED)
          .complement(Boolean.TRUE)),
  Blank(
      Source.POSIX,
      build(
          m -> {
            m.put(+' ', Boolean.TRUE);
            m.put(+'\t', Boolean.TRUE);
          }),
      difference(
          White_Space.unicode,
          unionOf(
              build(
                  m -> {
                    m.put(0x000a, Boolean.TRUE); // LF
                    m.put(0x000b, Boolean.TRUE); // VT
                    m.put(0x000c, Boolean.TRUE); // FF
                    m.put(0x000d, Boolean.TRUE); // CR
                    m.put(0x0085, Boolean.TRUE); // NEL
                  }),
              UnicodePredicates.LINE_SEPARATOR,
              UnicodePredicates.PARAGRAPH_SEPARATOR))),
  Cntrl(
      Source.POSIX,
      build(
          m -> {
            m.put(0x0000, 0x0020, Boolean.TRUE); // U+0000-001F
            m.put(0x007F, Boolean.TRUE); // U+007F
          }),
      UnicodePredicates.CONTROL),
  Print(
      Source.POSIX,
      build(
          m -> {
            m.putAll(Graph.ascii);
            m.put(0x0020, Boolean.TRUE);
          }),
      difference(
          unionOf(Graph.unicode, Blank.unicode),
          Cntrl.unicode)),
  // Bug fix (2026-09-07): this used to be a single-RangeSet constructor call using only
  // Hex_Digit.unicode (ASCII a-f/A-F/0-9 plus their fullwidth forms) -- both flag-insensitive
  // (ascii == unicode, so UNICODE_CHARACTER_CLASS was ignored) AND, independently, missing real
  // java.util.regex's actual widened definition. Verified against real java.util.regex: under
  // UNICODE_CHARACTER_CLASS, \p{XDigit} also matches any Unicode decimal digit from *any* script
  // (e.g. DEVANAGARI DIGIT ZERO, U+0966) -- because java.util.regex's widened XDigit is really
  // `Character.digit(cp, 16) != -1`, and Character.digit() accepts any digit whose numeric value
  // (0-9 for a decimal digit) is below the requested radix, not just the literal Unicode Hex_Digit
  // property. So the full-Unicode set is `union(Hex_Digit.unicode, Digit.unicode)`, not just
  // Hex_Digit.unicode; found via adding systematic ASCII-vs-Unicode coverage for every POSIX/java
  // class (see PosixAndJavaClassTest).
  XDigit(
      Source.POSIX,
      unionOf(Hex_Digit.unicode, Digit.unicode), /* slicedAscii=*/true),
  // Bug fix (2026-09-06): this used to read RegexCharacterClass.s.ascii, but RegexCharacterClass.s
  // itself (below) reads White_Space (right above) -- a genuine two-way dependency between this
  // enum and RegexCharacterClass, whichever's static initializer runs second sees the other's
  // not-yet-assigned enum constant as null (NullPointerException/ExceptionInInitializerError; see
  // remaining_work.md's "NamedCharClass/RegexCharacterClass circular static initialization" entry
  // for the repro that found this). This is the same literal ASCII whitespace set
  // RegexCharacterClass.s hardcodes -- inlined here instead of shared, so the dependency only ever
  // flows one way (RegexCharacterClass depends on NamedCharClass, never the reverse):
  // RegexCharacterClass.s now reads Space.ascii/Space.unicode instead of duplicating this literal.
  Space(
      Source.POSIX,
      build(
          m -> {
            m.put(+' ', Boolean.TRUE);
            m.put(+'\t', Boolean.TRUE);
            m.put(+'\n', Boolean.TRUE);
            m.put(0x000B, Boolean.TRUE);
            m.put(+'\f', Boolean.TRUE);
            m.put(+'\r', Boolean.TRUE);
          }),
      White_Space.unicode),
  ;


  enum Source {
    Block(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.in, CharacterClassPrefix.block).build()),
    Java(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.java).build()),
    POSIX(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.none).build()),
    Category(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.is, CharacterClassPrefix.general_category,
                                        CharacterClassPrefix.none).build()),
    UProperty(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.is).build()),
    Script(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.is, CharacterClassPrefix.script).build());

    final ImmutableSet<CharacterClassPrefix> allowedPrefixes;
    Source(ImmutableSet<CharacterClassPrefix> allowedPrefixes) {
      this.allowedPrefixes = allowedPrefixes;
    }
  }

  /**
   * Builds an immutable {@link CodePointMap}{@code <Boolean>} via a scratch {@link
   * ArrayCodePointMap}, for a hand-written literal set too irregular to express as a single
   * {@code put} call. Replaces the old {@code ImmutableRangeSet.Builder} chains -- {@code put}
   * (unlike {@code appendSorted}, which the generated {@code UnicodePredicates} uses) tolerates
   * entries added out of order, which several of the literals below are (e.g. {@code Hex_Digit}'s
   * a-f/A-F/0-9/fullwidth-digits ordering).
   */
  private static CodePointMap<Boolean> build(java.util.function.Consumer<MutableCodePointMap<Boolean>> filler) {
    ArrayCodePointMap<Boolean> result = new ArrayCodePointMap<>();
    filler.accept(result);
    return result;
  }

  /**
   * Unions any number of code-point sets that may legitimately overlap each other (e.g. two
   * different Unicode category predicates both claiming the same code point). {@link
   * CodePointMap#union} already tolerates overlap (last writer wins, and every set here agrees on
   * {@code Boolean.TRUE} wherever they overlap), so this is just a repeated {@code union} --
   * unlike the old {@code ImmutableRangeSet.Builder}, which threw on overlapping ranges and needed
   * a separate {@code TreeRangeSet}-based {@code union} helper to work around that.
   */
  @SafeVarargs
  private static CodePointMap<Boolean> unionOf(CodePointMap<Boolean>... sets) {
    CodePointMap<Boolean> merged = sets[0];
    for (int i = 1; i < sets.length; i++) {
      merged = merged.union(sets[i]);
    }
    return merged;
  }

  /**
   * {@code a} minus {@code b}: every code point {@code a} maps and {@code b} doesn't. Thin wrapper
   * over {@link CodePointMap#difference} kept for symmetry with {@link #unionOf} above.
   */
  private static CodePointMap<Boolean> difference(CodePointMap<Boolean> a, CodePointMap<Boolean> b) {
    return a.difference(b);
  }

  final Source source;
  // Which prefixes this constant may legally be looked up under -- defaults to source's own set,
  // but see the Digit constant above for the one case (a name shared between a POSIX class and a
  // Unicode binary property) that needs to override this to allow prefixes from both families.
  final ImmutableSet<CharacterClassPrefix> allowedPrefixes;
  final CodePointMap<Boolean> ascii;
  final CodePointMap<Boolean> unicode;

  NamedCharClass(Source source, NamedCharClass delegate) {
    this.source = source;
    this.allowedPrefixes = source.allowedPrefixes;
    this.ascii = delegate.ascii;
    this.unicode = delegate.unicode;
  }

  NamedCharClass(Source source, CodePointMap<Boolean> unicode) {
    this.source = source;
    this.allowedPrefixes = source.allowedPrefixes;
    this.ascii = unicode;
    this.unicode = unicode;
  }

  static final boolean SLICED_ASCII = true;
  NamedCharClass(
      Source source, CodePointMap<Boolean> unicode, boolean slicedAscii) {
    this(source.allowedPrefixes, source, unicode, slicedAscii);
  }

  // Only used by Digit -- see its own comment for why it needs prefixes from both Source.POSIX
  // and Source.UProperty rather than just inheriting one Source's set.
  NamedCharClass(
      ImmutableSet<CharacterClassPrefix> allowedPrefixes,
      Source source, CodePointMap<Boolean> unicode, boolean slicedAscii) {
    this.source = source;
    this.allowedPrefixes = allowedPrefixes;
    // UnicodePredicates.ascii is exactly one contiguous range ([0, 0x80)), so restricting to it
    // via intersection(min, max) is equivalent to a real set intersection here.
    this.ascii = unicode.intersection(0, 0x80);
    this.unicode = unicode;
  }

  NamedCharClass(
      Source source, CodePointMap<Boolean> ascii, CodePointMap<Boolean> unicode) {
    this.source = source;
    this.allowedPrefixes = source.allowedPrefixes;
    this.ascii = ascii;
    this.unicode = unicode;
  }

  CodePointMap<Boolean> get(CharacterClassPrefix prefix, int flags) {
    Preconditions.checkArgument(allowedPrefixes.contains(prefix));
    // Any Unicode-property-style prefix (\p{IsXxx}, \p{script=Xxx}, \p{block=Xxx},
    // \p{general_category=Xxx}) always means "exactly this Unicode-defined set" -- the
    // ASCII/full-Unicode split (governed by UNICODE_CHARACTER_CLASS) only applies to a bare POSIX
    // class name or a "java"-prefixed java.lang.Character-method class. This is what lets Digit
    // (see above) serve both \p{Digit} (flag-sensitive) and \p{IsDigit} (always full-Unicode) from
    // one constant instead of needing a separate always-full-Unicode duplicate.
    if (prefix != CharacterClassPrefix.none && prefix != CharacterClassPrefix.java) {
      return unicode;
    }
    return ((flags & Pattern.UNICODE_CHARACTER_CLASS) != 0) ? unicode : ascii;
  }

  enum CharacterClassPrefix {
    none,
    java,
    is,
    in,
    general_category,
    script,
    block,
  }

  enum RegexCharacterClass {
    DOT(build(m -> m.put(+'\n', Boolean.TRUE)).complement(Boolean.TRUE)),
    d(Digit),
    // Bug fix (2026-09-07): this used to be a single-RangeSet `D(Digit.unicode.complement())`,
    // which (like every other single-RangeSet constructor call here) is flag-insensitive -- so \D
    // always matched the complement of the *full-Unicode* digit set, ignoring
    // UNICODE_CHARACTER_CLASS entirely (unlike \S/\W below, which already complement `ascii`/
    // `unicode` separately). Found via PredefinedClassTest's \d/\D UNICODE_CHARACTER_CLASS
    // coverage, added alongside the NamedCharClass.Digit/PosixDigit merge (see its own doc).
    D(Digit.ascii.complement(Boolean.TRUE), Digit.unicode.complement(Boolean.TRUE)),
    h(build(
          m -> {
            m.put(+'\t', Boolean.TRUE);
            m.put(0x00A0, Boolean.TRUE);
            m.put(0x1680, Boolean.TRUE);
            m.put(0x180e, Boolean.TRUE);
            m.put(0x202f, Boolean.TRUE);
            m.put(0x205f, Boolean.TRUE);
            m.put(0x3000, Boolean.TRUE);
            m.put(0x2000, 0x200b, Boolean.TRUE);
          })),
    H(h.unicode.complement(Boolean.TRUE)),
    // Bug fix (2026-09-06): now delegates to NamedCharClass.Space instead of duplicating its own
    // hardcoded ASCII whitespace literal + a separate White_Space.unicode reference -- see Space's
    // own comment for why that duplication exists (breaking a circular static-init dependency) and
    // why this direction (RegexCharacterClass -> NamedCharClass, not the reverse) is safe.
    s(Space.ascii, Space.unicode),
    S(s.ascii.complement(Boolean.TRUE), s.unicode.complement(Boolean.TRUE)),
    v(build(
          m -> {
            m.put(+'\n', Boolean.TRUE);
            m.put(0x000B, Boolean.TRUE);
            m.put(+'\f', Boolean.TRUE);
            m.put(+'\r', Boolean.TRUE);
            m.put(0x0085, Boolean.TRUE);
            m.put(0x2028, Boolean.TRUE);
            m.put(0x2029, Boolean.TRUE);
          })),
    V(v.unicode.complement(Boolean.TRUE)),
    w(
        build(
            m -> {
              m.put(+'a', +'z' + 1, Boolean.TRUE);
              m.put(+'A', +'Z' + 1, Boolean.TRUE);
              m.put(+'0', +'9' + 1, Boolean.TRUE);
              m.put(+'_', Boolean.TRUE);
            }),
        unionOf(
            Alphabetic.unicode,
            Digit.unicode,
            UnicodePredicates.NON_SPACING_MARK,
            UnicodePredicates.COMBINING_SPACING_MARK,
            UnicodePredicates.ENCLOSING_MARK,
            UnicodePredicates.CONNECTOR_PUNCTUATION,
            Join_Control.unicode)),
    W(w.ascii.complement(Boolean.TRUE), w.unicode.complement(Boolean.TRUE)),
    R(build(
          m -> {
            m.put(+'\n', Boolean.TRUE);
            m.put(+'\r', Boolean.TRUE);
            m.put(0x000B, Boolean.TRUE);
            m.put(0x000C, Boolean.TRUE);
            m.put(0x0085, Boolean.TRUE);
            m.put(0x2028, Boolean.TRUE);
            m.put(0x2029, Boolean.TRUE);
          }));

    final CodePointMap<Boolean> ascii;
    final CodePointMap<Boolean> unicode;

    RegexCharacterClass(CodePointMap<Boolean> unicode) {
      this.ascii = unicode.intersection(0, 0x80);
      this.unicode = unicode;
    }

    RegexCharacterClass(
        CodePointMap<Boolean> ascii, CodePointMap<Boolean> unicode) {
      this.ascii = ascii;
      this.unicode = unicode;
    }

    RegexCharacterClass(NamedCharClass delegate) {
      this.ascii = delegate.ascii;
      this.unicode = delegate.unicode;
    }

    CodePointMap<Boolean> get(int flags) {
      return ((flags & Pattern.UNICODE_CHARACTER_CLASS) != 0) ? unicode : ascii;
    }
  }
}
