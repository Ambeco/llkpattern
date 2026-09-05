package com.tbohne.oldllkpattern;

import com.tbohne.llkpattern.CharacterClass.SimpleCharacterClass;
import java.util.Arrays;
import java.util.regex.Pattern;

enum NamedCharClass {
  // Java Character methods
  javaValidCodePoint(
      Source.Java, new SimpleCharacterClass().add(Character::isValidCodePoint)),
  javaBmpCodePoint(
      Source.Java, new SimpleCharacterClass().add(Character::isBmpCodePoint)),
  javaSupplementaryCodePoint(
      Source.Java, new SimpleCharacterClass().add(Character::isSupplementaryCodePoint)),
  javaLowerCase(Source.Java, new SimpleCharacterClass().add(Character::isLowerCase)),
  javaUpperCase(Source.Java, new SimpleCharacterClass().add(Character::isUpperCase)),
  javaTitleCase(Source.Java, new SimpleCharacterClass().add(Character::isTitleCase)),
  javaDigit(Source.Java, new SimpleCharacterClass().add(Character::isDigit)),
  javaDefined(Source.Java, new SimpleCharacterClass().add(Character::isDefined)),
  javaLetter(Source.Java, new SimpleCharacterClass().add(Character::isLetter)),
  javaLetterOrDigit(
      Source.Java, new SimpleCharacterClass().add(Character::isLetterOrDigit)),
  javaAlphabetic(Source.Java, new SimpleCharacterClass().add(Character::isAlphabetic)),
  javaIdeographic(Source.Java, new SimpleCharacterClass().add(Character::isIdeographic)),
  javaJavaIdentifierStart(
      Source.Java, new SimpleCharacterClass().add(Character::isJavaIdentifierStart)),
  javaJavaIdentifierPart(
      Source.Java, new SimpleCharacterClass().add(Character::isJavaIdentifierPart)),
  javaUnicodeIdentifierStart(
      Source.Java, new SimpleCharacterClass().add(Character::isUnicodeIdentifierStart)),
  javaUnicodeIdentifierPart(
      Source.Java, new SimpleCharacterClass().add(Character::isUnicodeIdentifierPart)),
  javaIdentifierIgnorable(
      Source.Java, new SimpleCharacterClass().add(Character::isIdentifierIgnorable)),
  javaSpaceChar(Source.Java, new SimpleCharacterClass().add(Character::isSpaceChar)),
  javaWhitespace(Source.Java, new SimpleCharacterClass().add(Character::isWhitespace)),
  javaISOControl(Source.Java, new SimpleCharacterClass().add(Character::isISOControl)),
  javaMirrored(Source.Java, new SimpleCharacterClass().add(Character::isMirrored)),

  // Unicode Categories https://www.unicode.org/reports/tr44/#GC_Values_Table
  Lu(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.UPPERCASE_LETTER)),
  // Uppercase_Letter(Source.UnicodeCategory, Lu),
  Ll(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.LOWERCASE_LETTER)),
  // Lowercase_Letter(Source.UnicodeCategory, Ll),
  Lt(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.TITLECASE_LETTER)),
  // Titlecase_Letter(Source.UnicodeCategory, Lt),
  LC(
      Source.UnicodeCategory,
      new SimpleCharacterClass()
          .add(
              Arrays.asList(
                  Character.UPPERCASE_LETTER,
                  Character.LOWERCASE_LETTER,
                  Character.TITLECASE_LETTER))),
  // Cased_Letter(Source.UnicodeCategory, LC),
  Lm(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.MODIFIER_LETTER)),
  // Modifier_Letter(Source.UnicodeCategory, Lm),
  Lo(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.OTHER_LETTER)),
  // Other_Letter(Source.UnicodeCategory, Lo),
  L(
      Source.UnicodeCategory,
      new SimpleCharacterClass()
          .add(
              Arrays.asList(
                  Character.UPPERCASE_LETTER,
                  Character.LOWERCASE_LETTER,
                  Character.TITLECASE_LETTER,
                  Character.MODIFIER_LETTER,
                  Character.OTHER_LETTER))),
  // Letter(Source.UnicodeCategory, L),
  Mn(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.NON_SPACING_MARK)),
  // Nonspacing_Mark(Source.UnicodeCategory, Mn),
  Mc(
      Source.UnicodeCategory,
      new SimpleCharacterClass().add(Character.COMBINING_SPACING_MARK)),
  // Spacing_Mark(Source.UnicodeCategory, Mc),
  Me(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.ENCLOSING_MARK)),
  // Enclosing_Mark(Source.UnicodeCategory, Me),
  M(
      Source.UnicodeCategory,
      new SimpleCharacterClass()
          .add(
              Arrays.asList(
                  Character.NON_SPACING_MARK,
                  Character.COMBINING_SPACING_MARK,
                  Character.ENCLOSING_MARK))),
  // Mark(Source.UnicodeCategory, M),
  Nd(
      Source.UnicodeCategory,
      new SimpleCharacterClass().add(Character.DECIMAL_DIGIT_NUMBER)),
  // Decimal_Number(Source.UnicodeCategory, Nd),
  Nl(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.LETTER_NUMBER)),
  // Letter_Number(Source.UnicodeCategory, Nl),
  No(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.OTHER_NUMBER)),
  // Other_Number(Source.UnicodeCategory, No),
  N(
      Source.UnicodeCategory,
      new SimpleCharacterClass()
          .add(
              Arrays.asList(
                  Character.DECIMAL_DIGIT_NUMBER,
                  Character.LETTER_NUMBER,
                  Character.OTHER_NUMBER))),
  // Number(Source.UnicodeCategory, N),
  Pc(
      Source.UnicodeCategory,
      new SimpleCharacterClass().add(Character.CONNECTOR_PUNCTUATION)),
  // Connector_Punctuation(Source.UnicodeCategory, Pc),
  Pd(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.DASH_PUNCTUATION)),
  // Dash_Punctuation(Source.UnicodeCategory, Pd),
  Ps(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.START_PUNCTUATION)),
  // Open_Punctuation(Source.UnicodeCategory, Ps),
  Pe(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.END_PUNCTUATION)),
  // Close_Punctuation(Source.UnicodeCategory, Pe),
  Pi(
      Source.UnicodeCategory,
      new SimpleCharacterClass().add(Character.INITIAL_QUOTE_PUNCTUATION)),
  // Initial_Punctuation(Source.UnicodeCategory, Pi),
  Pf(
      Source.UnicodeCategory,
      new SimpleCharacterClass().add(Character.FINAL_QUOTE_PUNCTUATION)),
  // Final_Punctuation(Source.UnicodeCategory, Pf),
  Po(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.OTHER_PUNCTUATION)),
  // Other_Punctuation(Source.UnicodeCategory, Po),
  P(
      Source.UnicodeCategory,
      new SimpleCharacterClass()
          .add(
              Arrays.asList(
                  Character.CONNECTOR_PUNCTUATION,
                  Character.DASH_PUNCTUATION,
                  Character.START_PUNCTUATION,
                  Character.END_PUNCTUATION,
                  Character.INITIAL_QUOTE_PUNCTUATION,
                  Character.FINAL_QUOTE_PUNCTUATION,
                  Character.OTHER_PUNCTUATION))),
  // Punctuation(Source.UnicodeCategory, P),
  Sm(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.MATH_SYMBOL)),
  // Math_Symbol(Source.UnicodeCategory, Sm),
  Sc(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.CURRENCY_SYMBOL)),
  // Currency_Symbol(Source.UnicodeCategory, Sc),
  Sk(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.MODIFIER_SYMBOL)),
  // Modifier_Symbol(Source.UnicodeCategory, Sk),
  So(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.OTHER_SYMBOL)),
  // Other_Symbol(Source.UnicodeCategory, So),
  S(
      Source.UnicodeCategory,
      new SimpleCharacterClass()
          .add(
              Arrays.asList(
                  Character.MATH_SYMBOL,
                  Character.CURRENCY_SYMBOL,
                  Character.MODIFIER_SYMBOL,
                  Character.OTHER_SYMBOL))),
  // Symbol(Source.UnicodeCategory, S),
  Zs(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.SPACE_SEPARATOR)),
  // Space_Separator(Source.UnicodeCategory, Zs),
  Zl(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.LINE_SEPARATOR)),
  // Line_Separator(Source.UnicodeCategory, Zl),
  Zp(
      Source.UnicodeCategory,
      new SimpleCharacterClass().add(Character.PARAGRAPH_SEPARATOR)),
  // Paragraph_Separator(Source.UnicodeCategory, Zp),
  Z(
      Source.UnicodeCategory,
      new SimpleCharacterClass()
          .add(
              Arrays.asList(
                  Character.SPACE_SEPARATOR,
                  Character.LINE_SEPARATOR,
                  Character.PARAGRAPH_SEPARATOR))),
  // Separator(Source.UnicodeCategory, Z),
  Cc(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.CONTROL)),
  // Control(Source.UnicodeCategory, Cc),
  Cf(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.FORMAT)),
  // Format(Source.UnicodeCategory, Cf),
  Cs(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.SURROGATE)),
  // Surrogate(Source.UnicodeCategory, Cs),
  Co(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.PRIVATE_USE)),
  // Private_Use(Source.UnicodeCategory, Co),
  Cn(Source.UnicodeCategory, new SimpleCharacterClass().add(Character.UNASSIGNED)),
  // Unassigned(Source.UnicodeCategory, Cn),
  C(
      Source.UnicodeCategory,
      new SimpleCharacterClass()
          .add(
              Arrays.asList(
                  Character.CONTROL,
                  Character.FORMAT,
                  Character.SURROGATE,
                  Character.PRIVATE_USE,
                  Character.UNASSIGNED))),
  // Other(Source.UnicodeCategory, C),

  // Unicode Binary Properties
  Alphabetic(Source.UProperty, javaAlphabetic),
  Ideographic(Source.UProperty, javaIdeographic),
  Letter(Source.UProperty, javaLetter),
  Lowercase(Source.UProperty, javaLowerCase),
  Uppercase(Source.UProperty, javaUpperCase),
  Titlecase(Source.UProperty, javaTitleCase),
  Punctuation(
      Source.UProperty,
      new SimpleCharacterClass()
          .add(
              Arrays.asList(
                  Character.CONNECTOR_PUNCTUATION,
                  Character.DASH_PUNCTUATION,
                  Character.START_PUNCTUATION,
                  Character.END_PUNCTUATION,
                  Character.INITIAL_QUOTE_PUNCTUATION,
                  Character.FINAL_QUOTE_PUNCTUATION,
                  Character.OTHER_PUNCTUATION))),
  Control(Source.UProperty, javaISOControl),
  White_Space(Source.UProperty, javaWhitespace),
  Digit(Source.UProperty, javaDigit),
  Hex_Digit(
      Source.UProperty,
      new SimpleCharacterClass() // not public in Java :(
                           .add(new CodePointDetails.CharRange('a', 'z'))
                           .add(new CodePointDetails.CharRange('A', 'Z'))
                           .add(new CodePointDetails.CharRange('0', '9'))
                           .add(new CodePointDetails.CharRange('\uFF41', '\uFF5A'))
                           .add(new CodePointDetails.CharRange('\uFF21', '\uFF3A'))
                           .add(new CodePointDetails.CharRange('\uFF10', '\uFF19'))),
  Join_Control(
      Source.UProperty,
      new SimpleCharacterClass().add("\u200C\u200D")), // not public in Java :(
  Noncharacter_Code_Point(
      Source.UProperty,
      new SimpleCharacterClass() // not public in Java :(
                           .add(
              "\ufdd0\ufdd1\ufdd2\ufdd3\ufdd4\ufdd5\ufdd6\ufdd7\ufdd8\ufdd9\ufdda\ufddb\ufddc\ufddd\ufdde\ufddf"
                  + "\ufde0\ufde1\ufde2\ufde3\ufde4\ufde5\ufde6\ufde7\ufde8\ufde9\ufdea\ufdeb\ufdec\ufded\ufdee\ufdef"
                  + "\ufffe\uffff\u1fffe\u1ffff\u2fffe\u2ffff\u3fffe\u3ffff\u4fffe\u4ffff\u5fffe\u5ffff\u6fffe\u6ffff"
                  + "\u7fffe\u7ffff\u8fffe\u8ffff\u9fffe\u9ffff\uafffe\uaffff\ubfffe\ubffff\ucfffe\ucffff\udfffe"
                  + "\udffff\uefffe\ueffff\uffffe\ufffff")
                           .add(0x10fffe)
                           .add(0x10ffff)),
  Assigned(
      Source.UProperty,
      new SimpleCharacterClass().add(Character.UNASSIGNED).shallowCloneNegate()),

  // POSIX character classes
  Lower(
      Source.POSIX,
      new SimpleCharacterClass().add(new CodePointDetails.CharRange('a', 'z')),
      Lowercase.unicode),
  Upper(
      Source.POSIX,
      new SimpleCharacterClass().add(new CodePointDetails.CharRange('A', 'Z')),
      Uppercase.unicode),
  ASCII(
      Source.POSIX,
      new SimpleCharacterClass().add(new CodePointDetails.CharRange('\u0000', '\u007F'))),
  Alpha(
      Source.POSIX,
      new SimpleCharacterClass()
          .add(new CodePointDetails.CharRange('a', 'z'))
          .add(new CodePointDetails.CharRange('A', 'Z')),
      Alphabetic.unicode),
  // Digit(d.ascii, Digit),
  Alnum(
      Source.POSIX,
      RegexCharacterClass.w.ascii,
      new SimpleCharacterClass(Alphabetic.unicode).add(Digit.unicode)),
  Punct(
      Source.POSIX,
      new SimpleCharacterClass().add("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"),
      Punctuation.unicode),
  Graph(
      Source.POSIX,
      new SimpleCharacterClass(Alnum.ascii).add(Punct.ascii),
      new SimpleCharacterClass(White_Space.unicode)
          .add(Arrays.asList(Character.CONTROL, Character.SURROGATE, Character.UNASSIGNED))
          .shallowCloneNegate()),
  Blank(
      Source.POSIX,
      new SimpleCharacterClass().add(' ').add('\t'),
      new SimpleCharacterClass(White_Space.unicode)
          .intersection(
              new SimpleCharacterClass()
                  .add("\n\u000b\u000c\r\u0085")
                  .add(Character.LINE_SEPARATOR)
                  .add(Character.PARAGRAPH_SEPARATOR)
                  .shallowCloneNegate())),
  Cntrl(
      Source.POSIX,
      new SimpleCharacterClass()
          .add(new CodePointDetails.CharRange('\u0000', '\u001f'))
          .add('\u007F'),
      new SimpleCharacterClass().add(Character.CONTROL)),
  Print(
      Source.POSIX,
      new SimpleCharacterClass(Graph.ascii).add('\u0020'),
      new SimpleCharacterClass(Graph.unicode)
          .add(Blank.unicode)
          .intersection(Cntrl.unicode.shallowCloneNegate())),
  XDigit(
      Source.POSIX,
      new SimpleCharacterClass()
          .add(new CodePointDetails.CharRange('a', 'z'))
          .add(new CodePointDetails.CharRange('A', 'Z'))
          .add(new CodePointDetails.CharRange('0', '9')),
      Hex_Digit.unicode),
  Space(Source.POSIX, RegexCharacterClass.s.ascii, White_Space.unicode),
  ;

  enum Source {
    Java,
    UProperty,
    POSIX,
    UnicodeCategory
  }

  final Source source;
  final SimpleCharacterClass ascii;
  final SimpleCharacterClass unicode;

  NamedCharClass(Source source, NamedCharClass delegate) {
    this.source = source;
    this.ascii = delegate.ascii;
    this.unicode = delegate.unicode;
  }

  NamedCharClass(Source source, SimpleCharacterClass unicode) {
    this.source = source;
    this.ascii = unicode;
    this.unicode = unicode;
  }

  NamedCharClass(
      Source source, SimpleCharacterClass ascii, SimpleCharacterClass unicode) {
    this.source = source;
    this.ascii = ascii;
    this.unicode = unicode;
  }

  SimpleCharacterClass get(int flags) {
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
    DOT(new SimpleCharacterClass().add(c -> true)),
    d(
        new SimpleCharacterClass().add(new CodePointDetails.CharRange('0', '9')),
        Digit.unicode),
    D(d.ascii.shallowCloneNegate(), d.unicode.shallowCloneNegate()),
    h(
        new SimpleCharacterClass()
            .add("\t\u00A0\u1680\u180e\u202f\u205f\u3000")
            .add(new CodePointDetails.CharRange('\u2000', '\u200a'))),
    H(h.unicode.shallowCloneNegate()),
    s(new SimpleCharacterClass().add(" \t\n\u000B\f\r"), White_Space.unicode),
    S(s.ascii.shallowCloneNegate(), s.unicode.shallowCloneNegate()),
    v(new SimpleCharacterClass().add("\n\u000B\f\r\u0085\u2028\u2029")),
    V(v.unicode.shallowCloneNegate()),
    w(
        new SimpleCharacterClass()
            .add(new CodePointDetails.CharRange('a', 'z'))
            .add(new CodePointDetails.CharRange('A', 'Z'))
            .add(new CodePointDetails.CharRange('0', '9'))
            .add('_'),
        new SimpleCharacterClass()
            .add(Alphabetic.unicode)
            .add(Digit.unicode)
            .add(Character.NON_SPACING_MARK)
            .add(Character.COMBINING_SPACING_MARK)
            .add(Character.ENCLOSING_MARK)
            .add(Character.CONNECTOR_PUNCTUATION)
            .add(Join_Control.unicode)),
    W(w.ascii.shallowCloneNegate(), w.unicode.shallowCloneNegate()),
    R(new SimpleCharacterClass().add("\n\r\u000B\u000C\u0085\u2028\u2029"));

    final SimpleCharacterClass ascii;
    final SimpleCharacterClass unicode;

    RegexCharacterClass(SimpleCharacterClass unicode) {
      this.ascii = unicode;
      this.unicode = unicode;
    }

    RegexCharacterClass(
        SimpleCharacterClass ascii, SimpleCharacterClass unicode) {
      this.ascii = ascii;
      this.unicode = unicode;
    }

    RegexCharacterClass(NamedCharClass delegate) {
      this.ascii = delegate.ascii;
      this.unicode = delegate.unicode;
    }

    SimpleCharacterClass get(int flags) {
      return ((flags & Pattern.UNICODE_CHARACTER_CLASS) != 0) ? unicode : ascii;
    }
  }
}
