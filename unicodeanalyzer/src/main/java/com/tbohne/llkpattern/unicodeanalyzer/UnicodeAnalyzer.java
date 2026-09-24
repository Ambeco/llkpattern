package com.tbohne.llkpattern.unicodeanalyzer;

import com.google.common.collect.Range;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

public class UnicodeAnalyzer {

	public static void main(String[] args) {
		printHeader();
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
		// JDK 21+ only. Looked up reflectively so this module still compiles on the JDK 17 Gradle runs on.
		for (String name : new String[] {"isEmoji", "isEmojiPresentation", "isEmojiModifier",
				"isEmojiModifierBase", "isEmojiComponent", "isExtendedPictographic"}) {
			intPredicate(name, reflectedCharacterPredicate(name));
		}

		categories();
		scripts();
		blocks();
		graphemeClusterBreak();
		printFooter();
	}

	// Extended grapheme cluster break (UAX #29) classification, ported from JDK 27's
	// jdk.internal.util.regex.Grapheme#getType so \X/\b{g} see the same JDK-27-pinned Unicode data
	// as every other named class here (see UnicodePredicates.java's own doc and README's "Unicode
	// data is currently pinned to JDK 27's tables" entry). EXTENDED_PICTOGRAPHIC isn't emitted here
	// -- it's already the generated isExtendedPictographic predicate above, and getType() itself
	// checks Character.isExtendedPictographic first, before any of the classification below, so
	// callers must do the same. OTHER isn't emitted either: it's the default for every code point
	// this method doesn't otherwise classify, i.e. "member of none of the GCB_* sets".
	private static final int GCB_OTHER = 0;
	private static final int GCB_CR = 1;
	private static final int GCB_LF = 2;
	private static final int GCB_CONTROL = 3;
	private static final int GCB_EXTEND = 4;
	private static final int GCB_ZWJ = 5;
	private static final int GCB_RI = 6;
	private static final int GCB_PREPEND = 7;
	private static final int GCB_SPACINGMARK = 8;
	private static final int GCB_L = 9;
	private static final int GCB_V = 10;
	private static final int GCB_T = 11;
	private static final int GCB_LV = 12;
	private static final int GCB_LVT = 13;

	public static void graphemeClusterBreak() {
		intPredicate("GCB_CR", cp -> graphemeClusterBreakType(cp) == GCB_CR);
		intPredicate("GCB_LF", cp -> graphemeClusterBreakType(cp) == GCB_LF);
		intPredicate("GCB_CONTROL", cp -> graphemeClusterBreakType(cp) == GCB_CONTROL);
		intPredicate("GCB_EXTEND", cp -> graphemeClusterBreakType(cp) == GCB_EXTEND);
		intPredicate("GCB_ZWJ", cp -> graphemeClusterBreakType(cp) == GCB_ZWJ);
		intPredicate("GCB_RI", cp -> graphemeClusterBreakType(cp) == GCB_RI);
		intPredicate("GCB_PREPEND", cp -> graphemeClusterBreakType(cp) == GCB_PREPEND);
		intPredicate("GCB_SPACINGMARK", cp -> graphemeClusterBreakType(cp) == GCB_SPACINGMARK);
		intPredicate("GCB_L", cp -> graphemeClusterBreakType(cp) == GCB_L);
		intPredicate("GCB_V", cp -> graphemeClusterBreakType(cp) == GCB_V);
		intPredicate("GCB_T", cp -> graphemeClusterBreakType(cp) == GCB_T);
		intPredicate("GCB_LV", cp -> graphemeClusterBreakType(cp) == GCB_LV);
		intPredicate("GCB_LVT", cp -> graphemeClusterBreakType(cp) == GCB_LVT);
	}

	// Hangul syllables (mirrors Grapheme.java's own constants).
	private static final int SYLLABLE_BASE = 0xAC00;
	private static final int LCOUNT = 19;
	private static final int VCOUNT = 21;
	private static final int TCOUNT = 28;
	private static final int NCOUNT = VCOUNT * TCOUNT;
	private static final int SCOUNT = LCOUNT * NCOUNT;

	// #tr29: SpacingMark exceptions -- ported verbatim from Grapheme.java.
	private static boolean isExcludedSpacingMark(int cp) {
		return cp == 0x102B || cp == 0x102C || cp == 0x1038 ||
				cp >= 0x1062 && cp <= 0x1064 ||
				cp >= 0x1067 && cp <= 0x106D ||
				cp == 0x1083 ||
				cp >= 0x1087 && cp <= 0x108C ||
				cp == 0x108F ||
				cp >= 0x109A && cp <= 0x109C ||
				cp == 0x1A61 || cp == 0x1A63 || cp == 0x1A64 ||
				cp == 0xAA7B || cp == 0xAA7D;
	}

	// Ported verbatim from jdk.internal.util.regex.Grapheme#getType (JDK 27), minus the leading
	// Character.isExtendedPictographic(cp) check -- callers (GraphemeCluster.java) do that first.
	@SuppressWarnings("fallthrough")
	private static int graphemeClusterBreakType(int cp) {
		if (cp < 0x007F) {
			if (cp < 32) {
				if (cp == 0x000D) return GCB_CR;
				if (cp == 0x000A) return GCB_LF;
				return GCB_CONTROL;
			}
			return GCB_OTHER;
		}
		if (Character.isExtendedPictographic(cp)) {
			// Handled separately by callers via the existing isExtendedPictographic set; excluded
			// from every GCB_* set here so the two classifications never overlap.
			return GCB_OTHER;
		}
		int type = Character.getType(cp);
		switch (type) {
		case Character.UNASSIGNED:
			if (cp == 0x0378) return GCB_OTHER;
			// fallthrough
		case Character.CONTROL:
		case Character.LINE_SEPARATOR:
		case Character.PARAGRAPH_SEPARATOR:
		case Character.SURROGATE:
			return GCB_CONTROL;
		case Character.FORMAT:
			if (cp == 0x200C || cp >= 0xE0020 && cp <= 0xE007F) return GCB_EXTEND;
			if (cp == 0x200D) return GCB_ZWJ;
			if (cp >= 0x0600 && cp <= 0x0605 ||
					cp == 0x06DD || cp == 0x070F ||
					cp == 0x0890 || cp == 0x0891 ||
					cp == 0x08E2 || cp == 0x110BD || cp == 0x110CD)
				return GCB_PREPEND;
			return GCB_CONTROL;
		case Character.NON_SPACING_MARK:
		case Character.ENCLOSING_MARK:
			return GCB_EXTEND;
		case Character.COMBINING_SPACING_MARK:
			if (isExcludedSpacingMark(cp)) return GCB_OTHER;
			return GCB_SPACINGMARK;
		case Character.OTHER_SYMBOL:
			if (cp >= 0x1F1E6 && cp <= 0x1F1FF) return GCB_RI;
			return GCB_OTHER;
		case Character.MODIFIER_LETTER:
		case Character.MODIFIER_SYMBOL:
			if (cp == 0xFF9E || cp == 0xFF9F || cp >= 0x1F3FB && cp <= 0x1F3FF) return GCB_EXTEND;
			return GCB_OTHER;
		case Character.OTHER_LETTER:
			if (cp == 0x0E33 || cp == 0x0EB3) return GCB_SPACINGMARK;
			if (cp >= 0x1100 && cp <= 0x11FF) {
				if (cp <= 0x115F) return GCB_L;
				if (cp <= 0x11A7) return GCB_V;
				return GCB_T;
			}
			int sindex = cp - SYLLABLE_BASE;
			if (sindex >= 0 && sindex < SCOUNT) {
				if (sindex % TCOUNT == 0) return GCB_LV;
				return GCB_LVT;
			}
			if (cp >= 0xA960 && cp <= 0xA97C) return GCB_L;
			if (cp >= 0xD7B0 && cp <= 0xD7C6 ||
					cp == 0x16D63 ||
					cp >= 0x16D67 && cp <= 0x16D6A)
				return GCB_V;
			if (cp >= 0xD7CB && cp <= 0xD7FB) return GCB_T;
			switch (cp) {
			case 0x0D4E:
			case 0x111C2:
			case 0x111C3:
			case 0x113D1:
			case 0x1193F:
			case 0x11941:
			case 0x11A84:
			case 0x11A85:
			case 0x11A86:
			case 0x11A87:
			case 0x11A88:
			case 0x11A89:
			case 0x11D46:
			case 0x11F02:
				return GCB_PREPEND;
			}
		}
		return GCB_OTHER;
	}

	public static void printHeader() {
		System.out.print("package com.tbohne.llkpattern;\n\n");
		System.out.print("import javax.annotation.processing.Generated;\n\n");
		System.out.print("/**\n");
		System.out.print(" * ⚠️ DO NOT EDIT THIS FILE DIRECTLY ⚠️\n");
		System.out.print(" *\n");
		System.out.print(" * <p>Generated by {@code UnicodeAnalyzer} (see the {@code unicodeanalyzer} module) from the\n");
		System.out.print(" * running JDK's own {@link Character} tables. Any changes made directly to this file will be\n");
		System.out.print(" * lost the next time it is regenerated -- {@code UnicodeAnalyzer} is run manually, as needed\n");
		System.out.print(" * (e.g. after a JDK upgrade changes Unicode data), not as part of the normal build.\n");
		System.out.print(" */\n");
		System.out.printf("@Generated(value = \"com.tbohne.llkpattern.unicodeanalyzer.UnicodeAnalyzer\", date = \"%s\")\n", LocalDate.now());
		System.out.print("class UnicodePredicates {\n");
		printCodePointMap("ascii", java.util.List.of(Range.closedOpen(0x0, 0x80)));
	}

	public static void printFooter() {
		System.out.print("}\n");
	}

	private static IntPredicate reflectedCharacterPredicate(String methodName) {
		java.lang.reflect.Method method;
		try {
			method = Character.class.getMethod(methodName, int.class);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException("Character." + methodName + "(int) needs JDK 21+, but this is JDK "
					+ System.getProperty("java.version") + ". Run UnicodeAnalyzer with the newest installed JDK "
					+ "(see CLAUDE.md, \"Regenerating UnicodePredicates.java\").", e);
		}
		return codePoint -> {
			try {
				return (Boolean) method.invoke(null, codePoint);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		};
	}

	public static void intPredicate(String name, IntPredicate predicate) {
		// Emitted as its own method (rather than inline in the field initializer) so that this
		// field's builder chain doesn't count against the class's single shared <clinit> method,
		// which has a 64KB bytecode limit that the combined initializers of ~1000 fields exceed.
		// See UnicodePredicates.java, and remaining_work.md "code too large".
		List<Range<Integer>> ranges = new ArrayList<>();
		int i=0;
		while (i<=0x10FFFF) {
			while (i<=0x10FFFF && !predicate.test(i))
				++i;
			int min = i;
			while (i<=0x10FFFF && predicate.test(i))
				++i;
			int max = i;
			if (min < max) {
				ranges.add(Range.closedOpen(min, max));
			}
		}
		printCodePointMap(name, ranges);
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
			// A plain string switch rather than reflection over the fields above, so it survives
			// R8/ProGuard field stripping/renaming.
			System.out.print("/** The set for {@code Character.UnicodeScript#name()} {@code enumName}, or {@code null} if unknown. */\n");
			System.out.print("static CodePointSet scriptByEnumName(String enumName) {\n");
			System.out.print("\tswitch (enumName) {\n");
			for (Character.UnicodeScript script : Character.UnicodeScript.values()) {
				System.out.printf("\t\tcase \"%s\": return %s;\n", script.name(), script.name());
			}
			System.out.print("\t\tdefault: return null;\n");
			System.out.print("\t}\n");
			System.out.print("}\n\n");
		}

	public static void blocks() {
		// LinkedHashMap: blocks come out in ascending code point order, so the output is deterministic.
		Map<Character.UnicodeBlock, Set<Range<Integer>>> ranges = new java.util.LinkedHashMap<>();
		int i=0;
		while (i<=0x10FFFF) {
			int min = i;
			Character.UnicodeBlock block = Character.UnicodeBlock.of(i);
			i++;
			while (i<=0x10FFFF && Character.UnicodeBlock.of(i) == block)
				++i;
			int max = i;
			// of() returns null for code points in no block; those are simply not a block.
			if (block != null) {
				ranges.computeIfAbsent(block, b -> new HashSet<>()).add(Range.closedOpen(min, max));
			}
		}
		// Block field names are prefixed: several collide with script names (GREEK, ARABIC, ...).
		for (Map.Entry<Character.UnicodeBlock, Set<Range<Integer>>> entry : ranges.entrySet()) {
			printRanges("BLOCK_" + entry.getKey().toString(), entry.getValue());
		}
		// Same shrinker-safe string switch as scriptByEnumName, keyed by UnicodeBlock#toString().
		System.out.print("/** The set for {@code Character.UnicodeBlock#toString()} {@code enumName}, or {@code null} if unknown. */\n");
		System.out.print("static CodePointSet blockByEnumName(String enumName) {\n");
		System.out.print("\tswitch (enumName) {\n");
		for (Character.UnicodeBlock block : ranges.keySet()) {
			System.out.printf("\t\tcase \"%s\": return BLOCK_%s;\n", block, block);
		}
		System.out.print("\t\tdefault: return null;\n");
		System.out.print("\t}\n");
		System.out.print("}\n\n");
	}

	public static void printRanges(String name, Set<Range<Integer>> rangeSet) {
		// rangeSet comes out of a HashMap/HashSet, so it arrives in no particular order --
		// CodePointMap.MutableCodePointMap#appendSorted requires ascending min (see
		// printCodePointMap), unlike the old ImmutableRangeSet.Builder this replaced.
		List<Range<Integer>> ranges = new ArrayList<>(rangeSet);
		ranges.sort(Comparator.comparing(Range::lowerEndpoint));
		printCodePointMap(name, ranges);
	}

	/**
	 * Emits one {@code private static CodePointSet init_NAME()}/{@code static final CodePointSet
	 * NAME} field pair, built via {@code ArrayCodePointSet#appendSorted}. {@code ranges} must
	 * already be in ascending {@code min} order and pairwise disjoint -- see the comment in
	 * intPredicate() for why each field gets its own method (the shared {@code <clinit>}'s 64KB
	 * bytecode limit).
	 */
	public static void printCodePointMap(String name, List<Range<Integer>> ranges) {
		System.out.printf("private static CodePointSet init_%s() {\n", name);
		System.out.print("\tArrayCodePointSet result = new ArrayCodePointSet();\n");
		System.out.printf("\tresult.ensureCapacity(%d);\n", ranges.size());
		for (Range<Integer> range : ranges) {
			System.out.printf("\tresult.appendSorted(0x%x, 0x%x);\n", range.lowerEndpoint(), range.upperEndpoint());
		}
		System.out.print("\treturn result;\n");
		System.out.print("}\n");
		System.out.printf("static final CodePointSet %s = init_%s();\n\n", name, name);
	}
}
