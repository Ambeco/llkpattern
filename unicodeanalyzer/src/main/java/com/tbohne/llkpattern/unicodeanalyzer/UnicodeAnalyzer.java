package com.tbohne.llkpattern.unicodeanalyzer;

import com.google.common.collect.Range;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

public class UnicodeAnalyzer {

	public static void main(String[] args) {
		intPredicate("isValidCodePoint", Character::isValidCodePoint);
		intPredicate("isBmpCodePoint", Character::isBmpCodePoint);
		intPredicate("isSupplementaryCodePoint", Character::isSupplementaryCodePoint);
		intPredicate("isLowerCase", Character::isLowerCase);
		intPredicate("isUpperCase", Character::isUpperCase);
		intPredicate("isTitleCase", Character::isTitleCase);
		intPredicate("isDigit", Character::isDigit);
		intPredicate("isDefined", Character::isDefined);
		intPredicate("isLetter", Character::isLetter);
		intPredicate("isLetterOrDigit", Character::isLetterOrDigit);
		intPredicate("isAlphabetic", Character::isAlphabetic);
		intPredicate("isIdeographic", Character::isIdeographic);
		intPredicate("isJavaIdentifierStart", Character::isJavaIdentifierStart);
		intPredicate("isJavaIdentifierPart", Character::isJavaIdentifierPart);
		intPredicate("isUnicodeIdentifierStart", Character::isUnicodeIdentifierStart);
		intPredicate("isUnicodeIdentifierPart", Character::isUnicodeIdentifierPart);
		intPredicate("isIdentifierIgnorable", Character::isIdentifierIgnorable);
		intPredicate("isSpaceChar", Character::isSpaceChar);
		intPredicate("isWhitespace", Character::isWhitespace);
		intPredicate("isISOControl", Character::isISOControl);
		intPredicate("isMirrored", Character::isMirrored);

		categories();
		scripts();
		blocks();
	}

	public static void intPredicate(String name, IntPredicate predicate) {
		System.out.printf("ImmutableRangeSet<Integer> %s = new ImmutableRangeSet.Builder<Integer>()\n", name);
		int i=0;
		while (i<=0x10FFFF) {
			while (i<=0x10FFFF && !predicate.test(i))
				++i;
			int min = i;
			while (i<=0x10FFFF && predicate.test(i))
				++i;
			int max = i;
			if (min + 1 == max) {
				System.out.printf("\t.add(Range.singleton(0x%x))\n", min);
			} else {
				System.out.printf("\t.add(Range.closedOpen(0x%x, 0x%x))\n", min, max);
			}
		}
		System.out.print("\t.build();\n");
	}

	public static void categories() {
		Map<Byte, Set<Range<Integer>>> ranges = new HashMap<>(31);
		for (byte j=0; j<31; j++) {
			ranges.put(j, new HashSet<>());
			}
		int i=0;
		while (i<=0x10FFFF) {
			int min = i;
			byte category = (byte) Character.getType(i);
			i++;
			while (i<=0x10FFFF && Character.getType(i) == category)
				++i;
			int max = i;
			ranges.get(category).add(Range.closedOpen(min, max));
		}
		printRanges("UNASSIGNED", ranges.get(Character.UNASSIGNED));
		printRanges("UPPERCASE_LETTER", ranges.get(Character.UPPERCASE_LETTER));
		printRanges("LOWERCASE_LETTER", ranges.get(Character.LOWERCASE_LETTER));
		printRanges("TITLECASE_LETTER", ranges.get(Character.TITLECASE_LETTER));
		printRanges("MODIFIER_LETTER", ranges.get(Character.MODIFIER_LETTER));
		printRanges("OTHER_LETTER", ranges.get(Character.OTHER_LETTER));
		printRanges("NON_SPACING_MARK", ranges.get(Character.NON_SPACING_MARK));
		printRanges("ENCLOSING_MARK", ranges.get(Character.ENCLOSING_MARK));
		printRanges("COMBINING_SPACING_MARK", ranges.get(Character.COMBINING_SPACING_MARK));
		printRanges("DECIMAL_DIGIT_NUMBER", ranges.get(Character.DECIMAL_DIGIT_NUMBER));
		printRanges("LETTER_NUMBER", ranges.get(Character.LETTER_NUMBER));
		printRanges("OTHER_NUMBER", ranges.get(Character.OTHER_NUMBER));
		printRanges("SPACE_SEPARATOR", ranges.get(Character.SPACE_SEPARATOR));
		printRanges("LINE_SEPARATOR", ranges.get(Character.LINE_SEPARATOR));
		printRanges("PARAGRAPH_SEPARATOR", ranges.get(Character.PARAGRAPH_SEPARATOR));
		printRanges("CONTROL", ranges.get(Character.CONTROL));
		printRanges("FORMAT", ranges.get(Character.FORMAT));
		printRanges("PRIVATE_USE", ranges.get(Character.PRIVATE_USE));
		printRanges("SURROGATE", ranges.get(Character.SURROGATE));
		printRanges("DASH_PUNCTUATION", ranges.get(Character.DASH_PUNCTUATION));
		printRanges("START_PUNCTUATION", ranges.get(Character.START_PUNCTUATION));
		printRanges("END_PUNCTUATION", ranges.get(Character.END_PUNCTUATION));
		printRanges("CONNECTOR_PUNCTUATION", ranges.get(Character.CONNECTOR_PUNCTUATION));
		printRanges("OTHER_PUNCTUATION", ranges.get(Character.OTHER_PUNCTUATION));
		printRanges("MATH_SYMBOL", ranges.get(Character.MATH_SYMBOL));
		printRanges("CURRENCY_SYMBOL", ranges.get(Character.CURRENCY_SYMBOL));
		printRanges("MODIFIER_SYMBOL", ranges.get(Character.MODIFIER_SYMBOL));
		printRanges("OTHER_SYMBOL", ranges.get(Character.OTHER_SYMBOL));
		printRanges("INITIAL_QUOTE_PUNCTUATION", ranges.get(Character.INITIAL_QUOTE_PUNCTUATION));
		printRanges("FINAL_QUOTE_PUNCTUATION", ranges.get(Character.FINAL_QUOTE_PUNCTUATION));
	}

		public static void scripts() {
			Map<Character.UnicodeScript, Set<Range<Integer>>> ranges = new HashMap<>(200);
			for (Character.UnicodeScript script : Character.UnicodeScript.values()) {
				ranges.put(script, new HashSet<>());
			}
			int i=0;
			while (i<=0x10FFFF) {
				int min = i;
				Character.UnicodeScript script = Character.UnicodeScript.of(i);
				i++;
				while (i<=0x10FFFF && Character.UnicodeScript.of(i) == script)
					++i;
				int max = i;
				ranges.get(script).add(Range.closedOpen(min, max));
			}
			for (Character.UnicodeScript script : Character.UnicodeScript.values()) {
				printRanges(script.name(), ranges.get(script));
			}
		}

	public static void blocks() {
		Map<Character.UnicodeBlock, Set<Range<Integer>>> ranges = new HashMap<>(700);
		int i=0;
		while (i<=0x10FFFF) {
			int min = i;
			Character.UnicodeBlock block = Character.UnicodeBlock.of(i);
			i++;
			while (i<=0x10FFFF && Character.UnicodeBlock.of(i) == block)
				++i;
			int max = i;
			ranges.getOrDefault(block, new HashSet<>()).add(Range.closedOpen(min, max));
		}
		for (Map.Entry<Character.UnicodeBlock, Set<Range<Integer>>> entry : ranges.entrySet()) {
			printRanges(entry.getKey().toString(), entry.getValue());
		}
	}

	public static void printRanges(String name, Set<Range<Integer>> rangeSet) {
		System.out.printf("ImmutableRangeSet<Integer> %s = new ImmutableRangeSet.Builder<Integer>()\n", name);
		for (Range<Integer> range : rangeSet) {
			if (range.lowerEndpoint() + 1 == range.upperEndpoint()) {
				System.out.printf("\t.add(Range.singleton(0x%x))\n", range.lowerEndpoint());
			} else {
				System.out.printf("\t.add(Range.closedOpen(0x%x, 0x%x))\n", range.lowerEndpoint(), range.upperEndpoint());
			}
		}
		System.out.print("\t.build();\n");
	}
}
