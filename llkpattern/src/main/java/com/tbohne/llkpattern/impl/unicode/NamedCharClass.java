package com.tbohne.llkpattern.impl.unicode;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import org.checkerframework.checker.nullness.qual.Nullable;

public enum NamedCharClass {
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
  // \p{javaDigit}: always all Unicode digits (Character.isDigit), like \p{IsDigit} -- unlike the
  // bare POSIX \p{Digit}, which is ASCII-only by default. See the Digit constant below.
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
  // JDK 21+ emoji properties: java.util.regex only knows them from JDK 21, but they're generated
  // into UnicodePredicates regardless of the JDK this runs on.
  Emoji(Source.UProperty, UnicodePredicates.isEmoji),
  Emoji_Presentation(Source.UProperty, UnicodePredicates.isEmojiPresentation),
  Emoji_Modifier(Source.UProperty, UnicodePredicates.isEmojiModifier),
  Emoji_Modifier_Base(Source.UProperty, UnicodePredicates.isEmojiModifierBase),
  Emoji_Component(Source.UProperty, UnicodePredicates.isEmojiComponent),
  Extended_Pictographic(Source.UProperty, UnicodePredicates.isExtendedPictographic),
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
  // The Unicode White_Space property is NOT Character.isWhitespace(), which excludes U+00A0, U+202F and U+205F as
  // non-breaking (and U+180E left White_Space in Unicode 6.3). Hand-built rather than reused from
  // RegexCharacterClass's h/v, to avoid the NamedCharClass<->RegexCharacterClass static-init cycle (see Space).
  White_Space(
      Source.UProperty,
      build(
          m -> {
            m.append(+'\t', +'\r' + 1); // U+0009-000D
            m.add(+' ');
            m.add(0x0085);
            m.add(0x00A0);
            m.add(0x1680);
            m.append(0x2000, 0x200B);
            m.add(0x2028);
            m.add(0x2029);
            m.add(0x202F);
            m.add(0x205F);
            m.add(0x3000);
          })),
  // Two distinct java.util.regex predicates are named "Digit", and this constant serves both via its
  // ascii/unicode sets: the POSIX \p{Digit} (ASCII [0-9] unless UNICODE_CHARACTER_CLASS widens it) and the Unicode
  // binary property \p{IsDigit} (ALWAYS all Unicode decimal digits; the flag never applies). A bare \p{Digit}
  // selects the POSIX one, so the Is prefix is the only way to the other (\p{javaDigit} is a third spelling of
  // the always-Unicode set). get()'s prefix check makes the `is` half full-Unicode regardless of flags; the
  // prefix set below equals Source.POSIX's (which also allows `is`), kept because Digit's Source is UProperty.
  Digit(
      ImmutableSet.of(CharacterClassPrefix.none, CharacterClassPrefix.is),
      Source.UProperty, javaDigit.unicode, /* slicedAscii=*/true),
  // Hex_Digit is a-f/A-F only (a past bug ranged over the full alphabet; PosixAndJavaClassTest).
  Hex_Digit(
      Source.UProperty,
      build(
          m -> {
            m.append(+'a', +'f' + 1);
            m.append(+'A', +'F' + 1);
            m.append(+'0', +'9' + 1);
            m.append(0xFF41, 0xFF47);
            m.append(0xFF21, 0xFF27);
            m.append(0xFF10, 0xFF1A);
          })),
  Join_Control(
      Source.UProperty, build(m -> m.append(0x200C, 0x200E))),
  Noncharacter_Code_Point(
      Source.UProperty,
      build( // not public in Java :(
          m -> {
            m.append(0xFDD0, 0xFDF0);
            m.append(0xFFFE, 0x10000);
            m.append(0x1FFFE, 0x20000);
            m.append(0x2FFFE, 0x30000);
            m.append(0x3FFFE, 0x40000);
            m.append(0x4FFFE, 0x50000);
            m.append(0x5FFFE, 0x60000);
            m.append(0x6FFFE, 0x70000);
            m.append(0x7FFFE, 0x80000);
            m.append(0x8FFFE, 0x90000);
            m.append(0x9FFFE, 0xA0000);
            m.append(0xAFFFE, 0xB0000);
            m.append(0xBFFFE, 0xC0000);
            m.append(0xCFFFE, 0xD0000);
            m.append(0xDFFFE, 0xE0000);
            m.append(0xEFFFE, 0xF0000);
            m.append(0xFFFFE, 0x100000);
            m.append(0x10FFFE, 0x110000);
          })),
  Assigned(
      Source.UProperty,
      UnicodePredicates.UNASSIGNED.complement()),
  // \p{IsWord}, also the set \w means under UNICODE_CHARACTER_CLASS (RegexCharacterClass.w.unicode).
  Word(
      Source.UProperty,
      unionOf(
          Alphabetic.unicode,
          Digit.unicode,
          UnicodePredicates.NON_SPACING_MARK,
          UnicodePredicates.COMBINING_SPACING_MARK,
          UnicodePredicates.ENCLOSING_MARK,
          UnicodePredicates.CONNECTOR_PUNCTUATION,
          Join_Control.unicode)),

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
  // slicedAscii intersects with UnicodePredicates.ascii, reducing this union to ASCII [0-9A-Za-z] by default; a
  // single set once made \p{Alnum} ignore UNICODE_CHARACTER_CLASS (PosixAndJavaClassTest).
  Alnum(
      Source.POSIX,
      unionOf(Alphabetic.unicode, Digit.unicode), /* slicedAscii=*/true),
  Punct(
      Source.POSIX,
      build(
          m -> {
            m.append(0x0021, 0x0030);
            m.append(0x003a, 0x0041);
            m.append(0x005B, 0x0061);
            m.append(0x007B, 0x007F);
          }),
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
      build(
          m -> {
            m.add(+' ');
            m.add(+'\t');
          }),
      difference(
          White_Space.unicode,
          unionOf(
              build(
                  m -> {
                    m.add(0x000a); // LF
                    m.add(0x000b); // VT
                    m.add(0x000c); // FF
                    m.add(0x000d); // CR
                    m.add(0x0085); // NEL
                  }),
              UnicodePredicates.LINE_SEPARATOR,
              UnicodePredicates.PARAGRAPH_SEPARATOR))),
  Cntrl(
      Source.POSIX,
      build(
          m -> {
            m.append(0x0000, 0x0020); // U+0000-001F
            m.add(0x007F); // U+007F
          }),
      UnicodePredicates.CONTROL),
  Print(
      Source.POSIX,
      build(
          m -> {
            m.appendAll(Graph.ascii);
            m.add(0x0020);
          }),
      difference(
          unionOf(Graph.unicode, Blank.unicode),
          Cntrl.unicode)),
  // Under UNICODE_CHARACTER_CLASS, java.util.regex's XDigit is Character.digit(cp, 16) != -1, which accepts any
  // script's decimal digit (e.g. U+0966), so the full-Unicode set is Hex_Digit.unicode union Digit.unicode.
  // ascii and unicode differ (PosixAndJavaClassTest).
  XDigit(
      Source.POSIX,
      unionOf(Hex_Digit.unicode, Digit.unicode), /* slicedAscii=*/true),
  // A literal ASCII whitespace set, shared into RegexCharacterClass.s: reading RegexCharacterClass.s.ascii here
  // made a two-way static-init dependency, each initializer seeing the other's constants as null. So the
  // dependency flows one way (RegexCharacterClass -> NamedCharClass).
  Space(
      Source.POSIX,
      build(
          m -> {
            m.add(+' ');
            m.add(+'\t');
            m.add(+'\n');
            m.add(0x000B);
            m.add(+'\f');
            m.add(+'\r');
          }),
      White_Space.unicode),
  ;


  enum Source {
    Block(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.in, CharacterClassPrefix.block).build()),
    Java(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.java).build()),
    // Every POSIX class is also reachable as \p{IsXxx} (always full-Unicode, see get()) -- verified
    // against java.util.regex on JDK 17 and 25 for all 13 names, e.g. \p{IsASCII}, \p{IsLower}.
    POSIX(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.none, CharacterClassPrefix.is).build()),
    Category(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.is, CharacterClassPrefix.general_category,
                                        CharacterClassPrefix.none).build()),
    UProperty(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.is).build()),
    Script(new ImmutableSet.Builder<CharacterClassPrefix>().add(CharacterClassPrefix.is, CharacterClassPrefix.script).build());

    final ImmutableSet<CharacterClassPrefix> allowedPrefixes;
    Source(ImmutableSet<CharacterClassPrefix> allowedPrefixes) {
      this.allowedPrefixes = allowedPrefixes;
    }
  }

  // Builds an immutable set via a CodePointSetBuilder, for a hand-written literal too irregular for one append.
  // append (unlike appendSorted, used by the generated UnicodePredicates) tolerates out-of-order entries, which
  // several literals below are (e.g. Hex_Digit).
  private static CodePointSet build(java.util.function.Consumer<CodePointSetBuilder> filler) {
    CodePointSetBuilder result = CodePointSetBuilder.create();
    filler.accept(result);
    return result.build();
  }

  // Unions sets that may overlap (e.g. two category predicates claiming a code point); CodePointSet#union
  // already tolerates overlap.
  private static CodePointSet unionOf(CodePointSet... sets) {
    CodePointSet merged = sets[0];
    for (int i = 1; i < sets.length; i++) {
      merged = merged.union(sets[i]);
    }
    return merged;
  }

  // a minus b: a thin wrapper over difference(), for symmetry with unionOf.
  private static CodePointSet difference(CodePointSet a, CodePointSet b) {
    return a.difference(b);
  }

  /**
   * The Unicode script named {@code name} (a full name or ISO 15924 alias, case-insensitive, per {@link
   * Character.UnicodeScript#forName}), or null. Scripts aren't enum constants (~160): {@code UnicodePredicates}
   * generates a set per constant plus a string switch (shrinker-safe, unlike reflection). A script newer than
   * the checked-in {@code UnicodePredicates} is also null.
   */
  public static @Nullable CodePointSet scriptByName(String name) {
    Character.UnicodeScript script;
    try {
      script = Character.UnicodeScript.forName(name);
    } catch (IllegalArgumentException e) {
      return null;
    }
    return UnicodePredicates.scriptByEnumName(script.name());
  }

  /**
   * The Unicode block named {@code name} (per {@link Character.UnicodeBlock#forName}), or null. Same shape as
   * {@link #scriptByName}; a block newer than the checked-in {@code UnicodePredicates} is reported unknown.
   */
  public static @Nullable CodePointSet blockByName(String name) {
    Character.UnicodeBlock block;
    try {
      block = Character.UnicodeBlock.forName(name);
    } catch (IllegalArgumentException e) {
      return null;
    }
    return UnicodePredicates.blockByEnumName(block.toString());
  }

  /** True if {@code name} is a named class that may be looked up under the {@code Is} prefix. */
  public static boolean isNamedClass(String name) {
    try {
      return valueOfIs(name).allowedPrefixes.contains(CharacterClassPrefix.is);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  // The four binary properties java.util.regex also accepts spelled without underscores.
  private static final ImmutableSet<String> UNDERSCORE_OPTIONAL =
      ImmutableSet.of("HEX_DIGIT", "JOIN_CONTROL", "NONCHARACTER_CODE_POINT", "WHITE_SPACE");

  // valueOf for a name after \p{Is}: as in java.util.regex, binary-property and POSIX names are case-insensitive
  // there (\p{IsALPHABETIC}), categories (\p{IsLu}) stay case-sensitive.
  public static NamedCharClass valueOfIs(String name) {
    try {
      return valueOf(name);
    } catch (IllegalArgumentException e) {
      for (NamedCharClass c : values()) {
        // \p{IsASCII} is the one POSIX name java.util.regex matches case-sensitively.
        if (c == ASCII || c.source == Source.Category || !c.allowedPrefixes.contains(CharacterClassPrefix.is)) {
          continue;
        }
        String candidate = c.name();
        if (candidate.equalsIgnoreCase(name)
            || UNDERSCORE_OPTIONAL.contains(candidate.toUpperCase(java.util.Locale.ROOT))
                && candidate.replace("_", "").equalsIgnoreCase(name)) {
          return c;
        }
      }
      throw e;
    }
  }

  final Source source;
  // Prefixes this constant may be looked up under: its source's set, except Digit (see above), which needs both
  // families.
  final ImmutableSet<CharacterClassPrefix> allowedPrefixes;
  final CodePointSet ascii;
  final CodePointSet unicode;

  NamedCharClass(Source source, NamedCharClass delegate) {
    this.source = source;
    this.allowedPrefixes = source.allowedPrefixes;
    this.ascii = delegate.ascii;
    this.unicode = delegate.unicode;
  }

  NamedCharClass(Source source, CodePointSet unicode) {
    this.source = source;
    this.allowedPrefixes = source.allowedPrefixes;
    this.ascii = unicode;
    this.unicode = unicode;
  }

  private static final boolean SLICED_ASCII = true;
  NamedCharClass(
      Source source, CodePointSet unicode, boolean slicedAscii) {
    this(source.allowedPrefixes, source, unicode, slicedAscii);
  }

  // Only for Digit (see above): prefixes from both Source.POSIX and Source.UProperty.
  NamedCharClass(
      ImmutableSet<CharacterClassPrefix> allowedPrefixes,
      Source source, CodePointSet unicode, boolean slicedAscii) {
    this.source = source;
    this.allowedPrefixes = allowedPrefixes;
    // UnicodePredicates.ascii is exactly one contiguous range ([0, 0x80)), so restricting to it
    // via intersection(min, max) is equivalent to a real set intersection here.
    this.ascii = unicode.intersection(0, 0x80);
    this.unicode = unicode;
  }

  NamedCharClass(
      Source source, CodePointSet ascii, CodePointSet unicode) {
    this.source = source;
    this.allowedPrefixes = source.allowedPrefixes;
    this.ascii = ascii;
    this.unicode = unicode;
  }

  public CodePointSet get(CharacterClassPrefix prefix, int flags) {
    Preconditions.checkArgument(allowedPrefixes.contains(prefix));
    // Any Unicode-property prefix (\p{IsXxx}, script=, block=, general_category=) means exactly the Unicode set; the
    // ASCII/Unicode split (UNICODE_CHARACTER_CLASS) applies only to a bare POSIX name or a "java" one. That lets
    // Digit serve both \p{Digit} and \p{IsDigit}.
    if (prefix != CharacterClassPrefix.none && prefix != CharacterClassPrefix.java) {
      return unicode;
    }
    return ((flags & UnicodeFlags.UNICODE_CHARACTER_CLASS) != 0) ? unicode : ascii;
  }

  // What java.util.regex matches under CASE_INSENSITIVE: a substituted set, not a fold. The cased-letter families
  // match every cased letter, POSIX Upper/Lower any ASCII letter (every cased letter in Unicode mode); other
  // classes, including scripts and blocks, are unaffected. `plain` is what get() returned.
  public CodePointSet caseInsensitive(CharacterClassPrefix prefix, int flags, CodePointSet plain) {
    switch (this) {
      case Lu:
      case Ll:
      case Lt:
        return LC.unicode;
      case javaLowerCase:
      case javaUpperCase:
      case javaTitleCase:
      case Lowercase:
      case Uppercase:
      case Titlecase:
        return CaseSets.CASED;
      case Lower:
      case Upper:
        return prefix == CharacterClassPrefix.is || (flags & UnicodeFlags.UNICODE_CHARACTER_CLASS) != 0
            ? CaseSets.CASED
            : CaseSets.ASCII_LETTERS;
      default:
        return plain;
    }
  }

  private static final class CaseSets {
    static final CodePointSet CASED =
        unionOf(UnicodePredicates.isLowerCase, UnicodePredicates.isUpperCase, UnicodePredicates.isTitleCase);
    static final CodePointSet ASCII_LETTERS = build(m -> {
      m.append('A', 'Z' + 1);
      m.append('a', 'z' + 1);
    });
  }

  public enum CharacterClassPrefix {
    none,
    java,
    is,
    in,
    general_category,
    script,
    block,
  }

  public enum RegexCharacterClass {
    // "." without DOTALL: everything but the line terminators (all by default, only '\n' under UNIX_LINES;
    // PatternParser picks). Not reachable as an escape.
    DOT(build(
          m -> {
            m.add(+'\n');
            m.add(+'\r');
            m.add(0x0085);
            m.append(0x2028, 0x2029 + 1);
          }).complement()),
    DOT_UNIX_LINES(build(m -> m.add(+'\n')).complement()),
    d(Digit),
    // \D complements `ascii` and `unicode` separately, so (like \d, \S and \W) it honors
    // UNICODE_CHARACTER_CLASS. A single-set `Digit.unicode.complement()` would be flag-insensitive.
    D(Digit.ascii.complement(), Digit.unicode.complement()),
    h(build(
          m -> {
            m.add(+'\t');
            m.add(0x00A0);
            m.add(0x1680);
            m.add(0x180e);
            m.add(0x202f);
            m.add(0x205f);
            m.add(0x3000);
            m.append(0x2000, 0x200b);
          })),
    H(h.unicode.complement()),
    // Delegates to NamedCharClass.Space rather than duplicating its literal; this direction
    // (RegexCharacterClass -> NamedCharClass) is the safe one, see Space.
    s(Space.ascii, Space.unicode),
    S(s.ascii.complement(), s.unicode.complement()),
    v(build(
          m -> {
            m.add(+'\n');
            m.add(0x000B);
            m.add(+'\f');
            m.add(+'\r');
            m.add(0x0085);
            m.add(0x2028);
            m.add(0x2029);
          })),
    V(v.unicode.complement()),
    w(
        build(
            m -> {
              m.append(+'a', +'z' + 1);
              m.append(+'A', +'Z' + 1);
              m.append(+'0', +'9' + 1);
              m.add(+'_');
            }),
        Word.unicode),
    W(w.ascii.complement(), w.unicode.complement()),
    R(build(
          m -> {
            m.add(+'\n');
            m.add(+'\r');
            m.add(0x000B);
            m.add(0x000C);
            m.add(0x0085);
            m.add(0x2028);
            m.add(0x2029);
          }));

    final CodePointSet ascii;
    public final CodePointSet unicode;

    RegexCharacterClass(CodePointSet unicode) {
      this.ascii = unicode.intersection(0, 0x80);
      this.unicode = unicode;
    }

    RegexCharacterClass(
        CodePointSet ascii, CodePointSet unicode) {
      this.ascii = ascii;
      this.unicode = unicode;
    }

    RegexCharacterClass(NamedCharClass delegate) {
      this.ascii = delegate.ascii;
      this.unicode = delegate.unicode;
    }

    public CodePointSet get(int flags) {
      return ((flags & UnicodeFlags.UNICODE_CHARACTER_CLASS) != 0) ? unicode : ascii;
    }

    // A switch, not valueOf(String): Enum.valueOf plus a String allocation was ~2.5% of Pixel compile samples.
    // Null for anything that isn't an escape letter (DOT/DOT_UNIX_LINES are not reachable as escapes; \R is
    // handled by the caller).
    public static @Nullable RegexCharacterClass forEscape(int c) {
      switch (c) {
        case 'd': return d;
        case 'D': return D;
        case 'h': return h;
        case 'H': return H;
        case 's': return s;
        case 'S': return S;
        case 'v': return v;
        case 'V': return V;
        case 'w': return w;
        case 'W': return W;
        case 'R': return R;
        default: return null;
      }
    }
  }
}
