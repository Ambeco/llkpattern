package com.tbohne.llkpattern;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

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
  White_Space(Source.UProperty, javaWhitespace),
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
      new ImmutableRangeSet.Builder<Integer>()
                 .add(Range.closed(+'a', +'f')).add(Range.closed(+'A', +'F'))
                .add(Range.closed(+'0', +'9')).add(Range.closed(0xFF41, 0xFF46))
                .add(Range.closed(0xFF21, 0xFF26)).add(Range.closed(0xFF10, 0xFF19))
                .build()),
  Join_Control(
      Source.UProperty, new ImmutableRangeSet.Builder<Integer>().add(Range.closed(0x200C, 0x200D)).build()),
  Noncharacter_Code_Point(
      Source.UProperty,
      new ImmutableRangeSet.Builder<Integer>() // not public in Java :(
          .add(Range.closed(0xFDD0, 0xFDEF))
          .add(Range.closed(0xFFFE, 0xFFFF))
          .add(Range.closed(0x1FFFE, 0x1FFFF))
          .add(Range.closed(0x2FFFE, 0x2FFFF))
          .add(Range.closed(0x3FFFE, 0x3FFFF))
          .add(Range.closed(0x4FFFE, 0x4FFFF))
          .add(Range.closed(0x5FFFE, 0x5FFFF))
          .add(Range.closed(0x6FFFE, 0x6FFFF))
          .add(Range.closed(0x7FFFE, 0x7FFFF))
          .add(Range.closed(0x8FFFE, 0x8FFFF))
          .add(Range.closed(0x9FFFE, 0x9FFFF))
          .add(Range.closed(0xAFFFE, 0xAFFFF))
          .add(Range.closed(0xBFFFE, 0xBFFFF))
          .add(Range.closed(0xCFFFE, 0xCFFFF))
          .add(Range.closed(0xDFFFE, 0xDFFFF))
          .add(Range.closed(0xEFFFE, 0xEFFFF))
          .add(Range.closed(0xFFFFE, 0xFFFFF))
          .add(Range.closed(0x10FFFE, 0x10FFFF))
          .build()),
  Assigned(
      Source.UProperty,
      UnicodePredicates.UNASSIGNED.complement()),

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
  Alnum(
      Source.POSIX,
      unionOf(Alphabetic.unicode, Digit.unicode)),
  Punct(
      Source.POSIX,
      new ImmutableRangeSet.Builder<Integer>()
          .add(Range.closedOpen(0x0021, 0x0030))
          .add(Range.closedOpen(0x003a, 0x0041))
          .add(Range.closedOpen(0x005B, 0x0061))
          .add(Range.closedOpen(0x007B, 0x007F))
          .build(),
      Punctuation.unicode),
  Graph(
      Source.POSIX,
      unionOf(Alnum.ascii, Punct.ascii),
      unionOf(UnicodePredicates.isWhitespace,
              UnicodePredicates.CONTROL,
              UnicodePredicates.SURROGATE,
              UnicodePredicates.UNASSIGNED)
          .complement()),
  Blank(
      Source.POSIX,
      new ImmutableRangeSet.Builder<Integer>()
          .add(Range.singleton(+' '))
          .add(Range.singleton(+'\t'))
          .build(),
      White_Space.unicode.difference(
          union(
              new ImmutableRangeSet.Builder<Integer>()
            .add(Range.singleton(+'\n'))
            .add(Range.singleton(+'\u000b'))
            .add(Range.singleton(+'\u000c'))
            .add(Range.singleton(+'\r'))
            .add(Range.singleton(+'\u0085'))
                .build(),
              UnicodePredicates.LINE_SEPARATOR,
              UnicodePredicates.PARAGRAPH_SEPARATOR))),
  Cntrl(
      Source.POSIX,
      new ImmutableRangeSet.Builder<Integer>()
          .add(Range.closed(+'\u0000', +'\u001f'))
          .add(Range.singleton(+'\u007F'))
          .build(),
      UnicodePredicates.CONTROL),
  Print(
      Source.POSIX,
      new ImmutableRangeSet.Builder<Integer>()
          .addAll(Graph.ascii)
          .add(Range.singleton(0x0020))
          .build(),
      union(Graph.unicode, Blank.unicode)
          .difference(Cntrl.unicode)),
  XDigit(
      Source.POSIX,
      Hex_Digit.unicode),
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
      new ImmutableRangeSet.Builder<Integer>()
          .add(Range.singleton(+' '))
          .add(Range.singleton(+'\t'))
          .add(Range.singleton(+'\n'))
          .add(Range.singleton(0x000B))
          .add(Range.singleton(+'\f'))
          .add(Range.singleton(+'\r'))
          .build(),
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
   * Unions any number of code-point range sets that may legitimately overlap each other (e.g.
   * two different Unicode category predicates both claiming the same code point).
   *
   * <p>Unlike {@code ImmutableRangeSet.Builder}, whose {@code build()} throws {@code
   * IllegalArgumentException} the moment two {@code add}/{@code addAll} calls contribute
   * overlapping ranges (it's meant for building one range set from known-disjoint pieces, not for
   * unioning several potentially-overlapping ones), this always succeeds: it merges everything
   * into a mutable {@code TreeRangeSet} first (whose {@code addAll} coalesces overlaps instead of
   * rejecting them), then freezes the result. Use this instead of {@code Builder} whenever
   * combining more than one already-built range set -- did you mean to use this instead of a
   * {@code Builder} chain, if you're seeing "Overlapping ranges not permitted"?
   */
  @SafeVarargs
  private static ImmutableRangeSet<Integer> union(RangeSet<Integer>... sets) {
    TreeRangeSet<Integer> merged = TreeRangeSet.create();
    for (RangeSet<Integer> set : sets) {
      merged.addAll(set);
    }
    return ImmutableRangeSet.copyOf(merged);
  }

  final Source source;
  // Which prefixes this constant may legally be looked up under -- defaults to source's own set,
  // but see the Digit constant above for the one case (a name shared between a POSIX class and a
  // Unicode binary property) that needs to override this to allow prefixes from both families.
  final ImmutableSet<CharacterClassPrefix> allowedPrefixes;
  final ImmutableRangeSet<Integer> ascii;
  final ImmutableRangeSet<Integer> unicode;

  NamedCharClass(Source source, NamedCharClass delegate) {
    this.source = source;
    this.allowedPrefixes = source.allowedPrefixes;
    this.ascii = delegate.ascii;
    this.unicode = delegate.unicode;
  }

  NamedCharClass(Source source, ImmutableRangeSet<Integer> unicode) {
    this.source = source;
    this.allowedPrefixes = source.allowedPrefixes;
    this.ascii = unicode;
    this.unicode = unicode;
  }

  static final boolean SLICED_ASCII = true;
  NamedCharClass(
      Source source, ImmutableRangeSet<Integer> unicode, boolean slicedAscii) {
    this(source.allowedPrefixes, source, unicode, slicedAscii);
  }

  // Only used by Digit -- see its own comment for why it needs prefixes from both Source.POSIX
  // and Source.UProperty rather than just inheriting one Source's set.
  NamedCharClass(
      ImmutableSet<CharacterClassPrefix> allowedPrefixes,
      Source source, ImmutableRangeSet<Integer> unicode, boolean slicedAscii) {
    this.source = source;
    this.allowedPrefixes = allowedPrefixes;
    this.ascii = unicode.intersection(UnicodePredicates.ascii);
    this.unicode = unicode;
  }

  NamedCharClass(
      Source source, ImmutableRangeSet<Integer> ascii, ImmutableRangeSet<Integer> unicode) {
    this.source = source;
    this.allowedPrefixes = source.allowedPrefixes;
    this.ascii = ascii;
    this.unicode = unicode;
  }

  ImmutableRangeSet<Integer> get(CharacterClassPrefix prefix, int flags) {
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
    DOT(ImmutableRangeSet.<Integer>of(Range.singleton(+'\n')).complement()),
    d(Digit),
    // Bug fix (2026-09-07): this used to be a single-RangeSet `D(Digit.unicode.complement())`,
    // which (like every other single-RangeSet constructor call here) is flag-insensitive -- so \D
    // always matched the complement of the *full-Unicode* digit set, ignoring
    // UNICODE_CHARACTER_CLASS entirely (unlike \S/\W below, which already complement `ascii`/
    // `unicode` separately). Found via PredefinedClassTest's \d/\D UNICODE_CHARACTER_CLASS
    // coverage, added alongside the NamedCharClass.Digit/PosixDigit merge (see its own doc).
    D(Digit.ascii.complement(), Digit.unicode.complement()),
    h(new ImmutableRangeSet.Builder<Integer>()
          .add(Range.singleton(+'\t'))
          .add(Range.singleton(0x00A0))
          .add(Range.singleton(0x1680))
          .add(Range.singleton(0x180e))
          .add(Range.singleton(0x202f))
          .add(Range.singleton(0x205f))
          .add(Range.singleton(0x3000))
          .add(Range.closed(0x2000,0x200a))
          .build()),
    H(h.unicode.complement()),
    // Bug fix (2026-09-06): now delegates to NamedCharClass.Space instead of duplicating its own
    // hardcoded ASCII whitespace literal + a separate White_Space.unicode reference -- see Space's
    // own comment for why that duplication exists (breaking a circular static-init dependency) and
    // why this direction (RegexCharacterClass -> NamedCharClass, not the reverse) is safe.
    s(Space.ascii, Space.unicode),
    S(s.ascii.complement(), s.unicode.complement()),
    v(new ImmutableRangeSet.Builder<Integer>()
          .add(Range.singleton(+'\n'))
          .add(Range.singleton(0x000B))
          .add(Range.singleton(+'\f'))
          .add(Range.singleton(+'\r'))
          .add(Range.singleton(0x0085))
          .add(Range.singleton(0x2028))
          .add(Range.singleton(0x2029))
          .build()),
    V(v.unicode.complement()),
    w(
        new ImmutableRangeSet.Builder<Integer>()
            .add(Range.closed(+'a', +'z'))
            .add(Range.closed(+'A', +'Z'))
            .add(Range.closed(+'0', +'9'))
            .add(Range.singleton(+'_'))
            .build(),
        union(
            Alphabetic.unicode,
            Digit.unicode,
            UnicodePredicates.NON_SPACING_MARK,
            UnicodePredicates.COMBINING_SPACING_MARK,
            UnicodePredicates.ENCLOSING_MARK,
            UnicodePredicates.CONNECTOR_PUNCTUATION,
            Join_Control.unicode)),
    W(w.ascii.complement(), w.unicode.complement()),
    R(new ImmutableRangeSet.Builder<Integer>()
          .add(Range.singleton(+'\n'))
          .add(Range.singleton(+'\r'))
          .add(Range.singleton(0x000B))
          .add(Range.singleton(0x000C))
          .add(Range.singleton(0x0085))
          .add(Range.singleton(0x2028))
          .add(Range.singleton(0x2029))
          .build());

    final ImmutableRangeSet<Integer> ascii;
    final ImmutableRangeSet<Integer> unicode;

    RegexCharacterClass(ImmutableRangeSet<Integer> unicode) {
      this.ascii = unicode.intersection(UnicodePredicates.ascii);
      this.unicode = unicode;
    }

    RegexCharacterClass(
        ImmutableRangeSet<Integer> ascii, ImmutableRangeSet<Integer> unicode) {
      this.ascii = ascii;
      this.unicode = unicode;
    }

    RegexCharacterClass(NamedCharClass delegate) {
      this.ascii = delegate.ascii;
      this.unicode = delegate.unicode;
    }

    ImmutableRangeSet<Integer> get(int flags) {
      return ((flags & Pattern.UNICODE_CHARACTER_CLASS) != 0) ? unicode : ascii;
    }
  }

  @SafeVarargs
  private static ImmutableRangeSet<Integer> unionOf(ImmutableRangeSet<Integer>... rangeSets) {
    ImmutableSet<Range<Integer>>[] setRanges = new ImmutableSet[rangeSets.length];
    for (int i=0; i<rangeSets.length; i++) {
      setRanges[i] = rangeSets[i].asRanges();
    }
    return ImmutableRangeSet.unionOf(Iterables.concat(setRanges));
  }
}
