package com.tbohne.llkpattern;

/**
 * Extended grapheme cluster boundary detection (Unicode Standard Annex #29), ported from JDK 27's
 * {@code jdk.internal.util.regex.Grapheme}/{@code IndicConjunctBreak} so {@code \X} sees exactly
 * the same boundaries {@code java.util.regex} would. The GCB_* classification itself comes from
 * generated, JDK-27-pinned data ({@link UnicodePredicates}, see its own doc and README's "Unicode
 * data is currently pinned to JDK 27's tables" entry); {@link #isLinker}/{@link #isConsonant}/
 * {@link #isExtend} below are copied verbatim from {@code IndicConjunctBreak} instead of going
 * through the generator, since they're already given as literal hardcoded code point ranges in
 * the JDK source, not derived from a runtime {@code Character} query.
 */
final class GraphemeCluster {
	private GraphemeCluster() {}

	// Types -- mirrors jdk.internal.util.regex.Grapheme's own private int constants.
	private static final int OTHER = 0;
	private static final int CR = 1;
	private static final int LF = 2;
	private static final int CONTROL = 3;
	private static final int EXTEND = 4;
	private static final int ZWJ = 5;
	private static final int RI = 6;
	private static final int PREPEND = 7;
	private static final int SPACINGMARK = 8;
	private static final int L = 9;
	private static final int V = 10;
	private static final int T = 11;
	private static final int LV = 12;
	private static final int LVT = 13;
	private static final int EXTENDED_PICTOGRAPHIC = 14;

	private static final int FIRST_TYPE = 0;
	private static final int LAST_TYPE = 14;

	private static final boolean[][] RULES;
	static {
		RULES = new boolean[LAST_TYPE + 1][LAST_TYPE + 1];
		// GB 999 Any + Any -> default
		for (int i = FIRST_TYPE; i <= LAST_TYPE; i++)
			for (int j = FIRST_TYPE; j <= LAST_TYPE; j++)
				RULES[i][j] = true;
		// GB 6 L x (L | V | LV | LVT)
		RULES[L][L] = false;
		RULES[L][V] = false;
		RULES[L][LV] = false;
		RULES[L][LVT] = false;
		// GB 7 (LV | V) x (V | T)
		RULES[LV][V] = false;
		RULES[LV][T] = false;
		RULES[V][V] = false;
		RULES[V][T] = false;
		// GB 8 (LVT | T) x T
		RULES[LVT][T] = false;
		RULES[T][T] = false;
		// GB 9 x (Extend|ZWJ); GB 9a x SpacingMark; GB 9b Prepend x
		for (int i = FIRST_TYPE; i <= LAST_TYPE; i++) {
			RULES[i][EXTEND] = false;
			RULES[i][ZWJ] = false;
			RULES[i][SPACINGMARK] = false;
			RULES[PREPEND][i] = false;
		}
		// GB 4 (Control|CR|LF) +; GB 5 + (Control|CR|LF)
		for (int i = FIRST_TYPE; i <= LAST_TYPE; i++)
			for (int j = CR; j <= CONTROL; j++) {
				RULES[i][j] = true;
				RULES[j][i] = true;
			}
		// GB 3 CR x LF
		RULES[CR][LF] = false;
		// GB 11 Extended_Pictographic x (Extend|ZWJ)
		RULES[EXTENDED_PICTOGRAPHIC][EXTEND] = false;
		RULES[EXTENDED_PICTOGRAPHIC][ZWJ] = false;
	}

	private static int getType(int cp) {
		if (UnicodePredicates.isExtendedPictographic.contains(cp)) return EXTENDED_PICTOGRAPHIC;
		if (UnicodePredicates.GCB_CR.contains(cp)) return CR;
		if (UnicodePredicates.GCB_LF.contains(cp)) return LF;
		if (UnicodePredicates.GCB_CONTROL.contains(cp)) return CONTROL;
		if (UnicodePredicates.GCB_EXTEND.contains(cp)) return EXTEND;
		if (UnicodePredicates.GCB_ZWJ.contains(cp)) return ZWJ;
		if (UnicodePredicates.GCB_RI.contains(cp)) return RI;
		if (UnicodePredicates.GCB_PREPEND.contains(cp)) return PREPEND;
		if (UnicodePredicates.GCB_SPACINGMARK.contains(cp)) return SPACINGMARK;
		if (UnicodePredicates.GCB_L.contains(cp)) return L;
		if (UnicodePredicates.GCB_V.contains(cp)) return V;
		if (UnicodePredicates.GCB_T.contains(cp)) return T;
		if (UnicodePredicates.GCB_LV.contains(cp)) return LV;
		if (UnicodePredicates.GCB_LVT.contains(cp)) return LVT;
		return OTHER;
	}

	/**
	 * The next extended grapheme cluster boundary in {@code src}, starting from {@code off} (which
	 * is assumed to already be a boundary) and never scanning past {@code limit} -- a direct port
	 * of {@code jdk.internal.util.regex.Grapheme#nextBoundary}. A forward-only scan, so it fits
	 * this engine's single-pass model with no architectural change: see {@code
	 * MatcherConstruct.GraphemeClusterMatcherConstruct}.
	 */
	static int nextBoundary(String src, int off, int limit) {
		int ch0 = src.codePointAt(off);
		int ret = off + Character.charCount(ch0);
		int t0 = getType(ch0);
		int riCount = t0 == RI ? 1 : 0;
		boolean gb11 = t0 == EXTENDED_PICTOGRAPHIC;
		while (ret < limit) {
			int ch1 = src.codePointAt(ret);
			int t1 = getType(ch1);

			// GB9c
			if (isConsonant(ch0)) {
				int advance = checkIndicConjunctBreak(src, ret, limit);
				if (advance >= 0) {
					ret += advance;
					continue;
				}
			}

			if (gb11 && t0 == ZWJ && t1 == EXTENDED_PICTOGRAPHIC) {
				// continue for gb11
			} else if (riCount % 2 == 1 && t0 == RI && t1 == RI) {
				// continue for gb12
			} else if (RULES[t0][t1]) {
				if (ret > off) {
					break;
				} else {
					gb11 = t1 == EXTENDED_PICTOGRAPHIC;
					riCount = 0;
				}
			}

			riCount += (t1 == RI) ? 1 : 0;
			ch0 = ch1;
			t0 = t1;

			ret += Character.charCount(ch1);
		}
		return ret;
	}

	private static int checkIndicConjunctBreak(String src, int index, int limit) {
		boolean linkerFound = false;
		int advance = 0;

		while (index + advance < limit) {
			int ch1 = src.codePointAt(index + advance);
			advance += Character.charCount(ch1);

			if (isLinker(ch1)) {
				linkerFound = true;
			} else if (isConsonant(ch1)) {
				if (linkerFound) {
					return advance;
				} else {
					break;
				}
			} else if (!isExtend(ch1)) {
				break;
			}
		}
		return -1;
	}

	/**
	 * Whether there's an extended grapheme cluster boundary immediately before {@code pos} in
	 * {@code src} -- i.e. between {@code Character.codePointBefore(src, pos)} and {@code
	 * Character.codePointAt(src, pos)} -- given that {@code floor} is as far back as this may read
	 * (mirrors {@code Matcher#peekPrevious}'s own {@code lookFloor} bound). Callers must ensure
	 * there IS a code point immediately before {@code pos} (i.e. {@code pos > floor}) and one AT
	 * {@code pos}; the true-start/true-end edge cases (always a boundary) are the caller's job, the
	 * same split {@code WordBoundaryMatcherConstruct} already uses for {@code \b}/{@code \B}.
	 *
	 * <p>Unlike {@link #nextBoundary}, this is a genuinely LOCAL, self-contained check -- it never
	 * needs an assumed "last known boundary" anchor the way JDK 27's own {@code
	 * Pattern.GraphemeBound} does (rescanning forward from {@code matcher.last} on every check):
	 * every rule below either depends only on the immediately adjacent pair (the common case, GB3-
	 * GB9b, via {@link #RULES}) or is answered by a BOUNDED backward walk through one specific kind
	 * of run (GB9c's Indic-conjunct Extend/Linker chain, GB11's emoji-ZWJ Extend chain, GB12/13's
	 * regional-indicator run) -- see design.md's "Extended grapheme clusters" section for why only
	 * these three rules need more than the adjacent pair, and the worked regional-indicator example
	 * there. Checked in the same precedence JDK's own {@code nextBoundary} loop uses (GB9c, then
	 * GB11, then GB12/13, then the plain pairwise table) -- the three special cases can't actually
	 * overlap (a regional indicator, a ZWJ, and a Consonant are disjoint GCB/InCB classes), so the
	 * order only matters for matching JDK's own structure, not for correctness.
	 */
	static boolean isBoundary(CharSequence src, int pos, int floor) {
		// A position splitting a surrogate pair is never a boundary -- checked before any type
		// classification, exactly mirroring JDK 27's Pattern.GraphemeBound#match (a lone surrogate
		// would otherwise classify as GCB_CONTROL on both sides, which the plain pairwise table
		// treats as an unconditional break -- see GB4/GB5 in RULES -- so this guard is load-bearing,
		// not just an optimization).
		if (Character.isHighSurrogate(src.charAt(pos - 1)) && Character.isLowSurrogate(src.charAt(pos))) {
			return false;
		}

		int prevCp = Character.codePointBefore(src, pos);
		int prevStart = pos - Character.charCount(prevCp);
		int nextCp = Character.codePointAt(src, pos);
		int t0 = getType(prevCp);
		int t1 = getType(nextCp);

		// GB9c: Indic conjunct break -- "Consonant [Extend|Linker]* Linker [Extend|Linker]* x
		// Consonant". `pos` is the "x" position iff `nextCp` is a Consonant and the run of
		// Extend/Linker code points immediately before `pos` (which must include at least one
		// Linker) is itself immediately preceded by a Consonant.
		if (isConsonant(nextCp) && (isLinker(prevCp) || isExtend(prevCp))
				&& indicConjunctChainStartsAtConsonant(src, pos, floor)) {
			return false;
		}

		// GB11: emoji ZWJ sequences -- "Extended_Pictographic Extend* ZWJ x Extended_Pictographic".
		if (t0 == ZWJ && t1 == EXTENDED_PICTOGRAPHIC
				&& zwjChainStartsWithPictographic(src, prevStart, floor)) {
			return false;
		}

		// GB12/13: regional indicator (flag emoji) pairs -- don't break between two RIs iff an odd
		// number of RIs immediately precede this position (see design.md's worked example: the
		// SAME adjacent (RI, RI) pair is a boundary between two flags but not within one, so this
		// can never be answered by the pair alone).
		if (t0 == RI && t1 == RI) {
			return countConsecutiveRIBefore(src, pos, floor) % 2 == 0;
		}

		return RULES[t0][t1];
	}

	/**
	 * Walks backward from {@code pos} through the {@code [Extend|Linker]*} run GB9c allows, and
	 * reports whether the code point immediately before that run is a Consonant AND at least one
	 * Linker appeared in the run -- i.e. whether {@code pos} is the "x" position in "Consonant
	 * [Extend|Linker]* Linker [Extend|Linker]* x". Bounded by the run's own actual length, same
	 * complexity class as {@link #countConsecutiveRIBefore}/{@link #zwjChainStartsWithPictographic}.
	 */
	private static boolean indicConjunctChainStartsAtConsonant(CharSequence src, int pos, int floor) {
		int i = pos;
		boolean linkerSeen = false;
		while (i > floor) {
			int cp = Character.codePointBefore(src, i);
			if (isLinker(cp)) {
				linkerSeen = true;
			} else if (!isExtend(cp)) {
				return linkerSeen && isConsonant(cp);
			}
			i -= Character.charCount(cp);
		}
		return false; // Ran off the scannable region without finding the chain's leading Consonant.
	}

	/**
	 * Walks backward from {@code pos} (the start of a ZWJ already confirmed by the caller) through
	 * the {@code Extend*} run GB11 allows, and reports whether the code point immediately before
	 * that run is {@code Extended_Pictographic} -- i.e. whether the ZWJ genuinely continues an
	 * emoji sequence rather than merely preceding an unrelated pictograph.
	 */
	private static boolean zwjChainStartsWithPictographic(CharSequence src, int pos, int floor) {
		int i = pos;
		while (i > floor) {
			int cp = Character.codePointBefore(src, i);
			int type = getType(cp);
			if (type == EXTENDED_PICTOGRAPHIC) {
				return true;
			}
			if (type != EXTEND) {
				return false;
			}
			i -= Character.charCount(cp);
		}
		return false;
	}

	/**
	 * The count of consecutive regional-indicator code points ending immediately before {@code
	 * pos} -- exactly UAX #29's "number of RI characters before the break point" that GB12/13's
	 * parity rule keys on.
	 */
	private static int countConsecutiveRIBefore(CharSequence src, int pos, int floor) {
		int i = pos;
		int count = 0;
		while (i > floor) {
			int cp = Character.codePointBefore(src, i);
			if (getType(cp) != RI) {
				break;
			}
			count++;
			i -= Character.charCount(cp);
		}
		return count;
	}

	// Indic_Conjunct_Break=Linker/Consonant/Extend -- copied verbatim from JDK 27's
	// jdk.internal.util.regex.IndicConjunctBreak (Unicode 17.0's DerivedCoreProperties.txt).
	private static boolean isLinker(int cp) {
		return
			cp == 0x094D ||
			cp == 0x09CD ||
			cp == 0x0ACD ||
			cp == 0x0B4D ||
			cp == 0x0C4D ||
			cp == 0x0D4D ||
			cp == 0x1039 ||
			cp == 0x17D2 ||
			cp == 0x1A60 ||
			cp == 0x1B44 ||
			cp == 0x1BAB ||
			cp == 0xA9C0 ||
			cp == 0xAAF6 ||
			cp == 0x10A3F ||
			cp == 0x11133 ||
			cp == 0x113D0 ||
			cp == 0x1193E ||
			cp == 0x11A47 ||
			cp == 0x11A99 ||
			cp == 0x11F42;
	}

	private static boolean isExtend(int cp) {
		return
			(cp >= 0x0300 && cp <= 0x036F) ||
			(cp >= 0x0483 && cp <= 0x0489) ||
			(cp >= 0x0591 && cp <= 0x05BD) ||
			cp == 0x05BF ||
			cp == 0x05C1 ||
			cp == 0x05C2 ||
			cp == 0x05C4 ||
			cp == 0x05C5 ||
			cp == 0x05C7 ||
			(cp >= 0x0610 && cp <= 0x061A) ||
			(cp >= 0x064B && cp <= 0x065F) ||
			cp == 0x0670 ||
			(cp >= 0x06D6 && cp <= 0x06DC) ||
			(cp >= 0x06DF && cp <= 0x06E4) ||
			cp == 0x06E7 ||
			cp == 0x06E8 ||
			(cp >= 0x06EA && cp <= 0x06ED) ||
			cp == 0x0711 ||
			(cp >= 0x0730 && cp <= 0x074A) ||
			(cp >= 0x07A6 && cp <= 0x07B0) ||
			(cp >= 0x07EB && cp <= 0x07F3) ||
			cp == 0x07FD ||
			(cp >= 0x0816 && cp <= 0x0819) ||
			(cp >= 0x081B && cp <= 0x0823) ||
			(cp >= 0x0825 && cp <= 0x0827) ||
			(cp >= 0x0829 && cp <= 0x082D) ||
			(cp >= 0x0859 && cp <= 0x085B) ||
			(cp >= 0x0897 && cp <= 0x089F) ||
			(cp >= 0x08CA && cp <= 0x08E1) ||
			(cp >= 0x08E3 && cp <= 0x0902) ||
			cp == 0x093A ||
			cp == 0x093C ||
			(cp >= 0x0941 && cp <= 0x0948) ||
			(cp >= 0x0951 && cp <= 0x0957) ||
			cp == 0x0962 ||
			cp == 0x0963 ||
			cp == 0x0981 ||
			cp == 0x09BC ||
			cp == 0x09BE ||
			(cp >= 0x09C1 && cp <= 0x09C4) ||
			cp == 0x09D7 ||
			cp == 0x09E2 ||
			cp == 0x09E3 ||
			cp == 0x09FE ||
			cp == 0x0A01 ||
			cp == 0x0A02 ||
			cp == 0x0A3C ||
			cp == 0x0A41 ||
			cp == 0x0A42 ||
			cp == 0x0A47 ||
			cp == 0x0A48 ||
			(cp >= 0x0A4B && cp <= 0x0A4D) ||
			cp == 0x0A51 ||
			cp == 0x0A70 ||
			cp == 0x0A71 ||
			cp == 0x0A75 ||
			cp == 0x0A81 ||
			cp == 0x0A82 ||
			cp == 0x0ABC ||
			(cp >= 0x0AC1 && cp <= 0x0AC5) ||
			cp == 0x0AC7 ||
			cp == 0x0AC8 ||
			cp == 0x0AE2 ||
			cp == 0x0AE3 ||
			(cp >= 0x0AFA && cp <= 0x0AFF) ||
			cp == 0x0B01 ||
			cp == 0x0B3C ||
			cp == 0x0B3E ||
			cp == 0x0B3F ||
			(cp >= 0x0B41 && cp <= 0x0B44) ||
			(cp >= 0x0B55 && cp <= 0x0B57) ||
			cp == 0x0B62 ||
			cp == 0x0B63 ||
			cp == 0x0B82 ||
			cp == 0x0BBE ||
			cp == 0x0BC0 ||
			cp == 0x0BCD ||
			cp == 0x0BD7 ||
			cp == 0x0C00 ||
			cp == 0x0C04 ||
			cp == 0x0C3C ||
			(cp >= 0x0C3E && cp <= 0x0C40) ||
			(cp >= 0x0C46 && cp <= 0x0C48) ||
			(cp >= 0x0C4A && cp <= 0x0C4C) ||
			cp == 0x0C55 ||
			cp == 0x0C56 ||
			cp == 0x0C62 ||
			cp == 0x0C63 ||
			cp == 0x0C81 ||
			cp == 0x0CBC ||
			cp == 0x0CBF ||
			cp == 0x0CC0 ||
			cp == 0x0CC2 ||
			(cp >= 0x0CC6 && cp <= 0x0CC8) ||
			(cp >= 0x0CCA && cp <= 0x0CCD) ||
			cp == 0x0CD5 ||
			cp == 0x0CD6 ||
			cp == 0x0CE2 ||
			cp == 0x0CE3 ||
			cp == 0x0D00 ||
			cp == 0x0D01 ||
			cp == 0x0D3B ||
			cp == 0x0D3C ||
			cp == 0x0D3E ||
			(cp >= 0x0D41 && cp <= 0x0D44) ||
			cp == 0x0D57 ||
			cp == 0x0D62 ||
			cp == 0x0D63 ||
			cp == 0x0D81 ||
			cp == 0x0DCA ||
			cp == 0x0DCF ||
			(cp >= 0x0DD2 && cp <= 0x0DD4) ||
			cp == 0x0DD6 ||
			cp == 0x0DDF ||
			cp == 0x0E31 ||
			(cp >= 0x0E34 && cp <= 0x0E3A) ||
			(cp >= 0x0E47 && cp <= 0x0E4E) ||
			cp == 0x0EB1 ||
			(cp >= 0x0EB4 && cp <= 0x0EBC) ||
			(cp >= 0x0EC8 && cp <= 0x0ECE) ||
			cp == 0x0F18 ||
			cp == 0x0F19 ||
			cp == 0x0F35 ||
			cp == 0x0F37 ||
			cp == 0x0F39 ||
			(cp >= 0x0F71 && cp <= 0x0F7E) ||
			(cp >= 0x0F80 && cp <= 0x0F84) ||
			cp == 0x0F86 ||
			cp == 0x0F87 ||
			(cp >= 0x0F8D && cp <= 0x0F97) ||
			(cp >= 0x0F99 && cp <= 0x0FBC) ||
			cp == 0x0FC6 ||
			(cp >= 0x102D && cp <= 0x1030) ||
			(cp >= 0x1032 && cp <= 0x1037) ||
			cp == 0x103A ||
			cp == 0x103D ||
			cp == 0x103E ||
			cp == 0x1058 ||
			cp == 0x1059 ||
			(cp >= 0x105E && cp <= 0x1060) ||
			(cp >= 0x1071 && cp <= 0x1074) ||
			cp == 0x1082 ||
			cp == 0x1085 ||
			cp == 0x1086 ||
			cp == 0x108D ||
			cp == 0x109D ||
			(cp >= 0x135D && cp <= 0x135F) ||
			(cp >= 0x1712 && cp <= 0x1715) ||
			(cp >= 0x1732 && cp <= 0x1734) ||
			cp == 0x1752 ||
			cp == 0x1753 ||
			cp == 0x1772 ||
			cp == 0x1773 ||
			cp == 0x17B4 ||
			cp == 0x17B5 ||
			(cp >= 0x17B7 && cp <= 0x17BD) ||
			cp == 0x17C6 ||
			(cp >= 0x17C9 && cp <= 0x17D1) ||
			cp == 0x17D3 ||
			cp == 0x17DD ||
			(cp >= 0x180B && cp <= 0x180D) ||
			cp == 0x180F ||
			cp == 0x1885 ||
			cp == 0x1886 ||
			cp == 0x18A9 ||
			(cp >= 0x1920 && cp <= 0x1922) ||
			cp == 0x1927 ||
			cp == 0x1928 ||
			cp == 0x1932 ||
			(cp >= 0x1939 && cp <= 0x193B) ||
			cp == 0x1A17 ||
			cp == 0x1A18 ||
			cp == 0x1A1B ||
			cp == 0x1A56 ||
			(cp >= 0x1A58 && cp <= 0x1A5E) ||
			cp == 0x1A62 ||
			(cp >= 0x1A65 && cp <= 0x1A6C) ||
			(cp >= 0x1A73 && cp <= 0x1A7C) ||
			cp == 0x1A7F ||
			(cp >= 0x1AB0 && cp <= 0x1ADD) ||
			(cp >= 0x1AE0 && cp <= 0x1AEB) ||
			(cp >= 0x1B00 && cp <= 0x1B03) ||
			(cp >= 0x1B34 && cp <= 0x1B3D) ||
			cp == 0x1B42 ||
			cp == 0x1B43 ||
			(cp >= 0x1B6B && cp <= 0x1B73) ||
			cp == 0x1B80 ||
			cp == 0x1B81 ||
			(cp >= 0x1BA2 && cp <= 0x1BA5) ||
			(cp >= 0x1BA8 && cp <= 0x1BAA) ||
			cp == 0x1BAC ||
			cp == 0x1BAD ||
			cp == 0x1BE6 ||
			cp == 0x1BE8 ||
			cp == 0x1BE9 ||
			cp == 0x1BED ||
			(cp >= 0x1BEF && cp <= 0x1BF3) ||
			(cp >= 0x1C2C && cp <= 0x1C33) ||
			cp == 0x1C36 ||
			cp == 0x1C37 ||
			(cp >= 0x1CD0 && cp <= 0x1CD2) ||
			(cp >= 0x1CD4 && cp <= 0x1CE0) ||
			(cp >= 0x1CE2 && cp <= 0x1CE8) ||
			cp == 0x1CED ||
			cp == 0x1CF4 ||
			cp == 0x1CF8 ||
			cp == 0x1CF9 ||
			(cp >= 0x1DC0 && cp <= 0x1DFF) ||
			cp == 0x200D ||
			(cp >= 0x20D0 && cp <= 0x20F0) ||
			(cp >= 0x2CEF && cp <= 0x2CF1) ||
			cp == 0x2D7F ||
			(cp >= 0x2DE0 && cp <= 0x2DFF) ||
			(cp >= 0x302A && cp <= 0x302F) ||
			cp == 0x3099 ||
			cp == 0x309A ||
			(cp >= 0xA66F && cp <= 0xA672) ||
			(cp >= 0xA674 && cp <= 0xA67D) ||
			cp == 0xA69E ||
			cp == 0xA69F ||
			cp == 0xA6F0 ||
			cp == 0xA6F1 ||
			cp == 0xA802 ||
			cp == 0xA806 ||
			cp == 0xA80B ||
			cp == 0xA825 ||
			cp == 0xA826 ||
			cp == 0xA82C ||
			cp == 0xA8C4 ||
			cp == 0xA8C5 ||
			(cp >= 0xA8E0 && cp <= 0xA8F1) ||
			cp == 0xA8FF ||
			(cp >= 0xA926 && cp <= 0xA92D) ||
			(cp >= 0xA947 && cp <= 0xA951) ||
			cp == 0xA953 ||
			(cp >= 0xA980 && cp <= 0xA982) ||
			cp == 0xA9B3 ||
			(cp >= 0xA9B6 && cp <= 0xA9B9) ||
			cp == 0xA9BC ||
			cp == 0xA9BD ||
			cp == 0xA9E5 ||
			(cp >= 0xAA29 && cp <= 0xAA2E) ||
			cp == 0xAA31 ||
			cp == 0xAA32 ||
			cp == 0xAA35 ||
			cp == 0xAA36 ||
			cp == 0xAA43 ||
			cp == 0xAA4C ||
			cp == 0xAA7C ||
			cp == 0xAAB0 ||
			(cp >= 0xAAB2 && cp <= 0xAAB4) ||
			cp == 0xAAB7 ||
			cp == 0xAAB8 ||
			cp == 0xAABE ||
			cp == 0xAABF ||
			cp == 0xAAC1 ||
			cp == 0xAAEC ||
			cp == 0xAAED ||
			cp == 0xABE5 ||
			cp == 0xABE8 ||
			cp == 0xABED ||
			cp == 0xFB1E ||
			(cp >= 0xFE00 && cp <= 0xFE0F) ||
			(cp >= 0xFE20 && cp <= 0xFE2F) ||
			cp == 0xFF9E ||
			cp == 0xFF9F ||
			cp == 0x101FD ||
			cp == 0x102E0 ||
			(cp >= 0x10376 && cp <= 0x1037A) ||
			(cp >= 0x10A01 && cp <= 0x10A03) ||
			cp == 0x10A05 ||
			cp == 0x10A06 ||
			(cp >= 0x10A0C && cp <= 0x10A0F) ||
			(cp >= 0x10A38 && cp <= 0x10A3A) ||
			cp == 0x10AE5 ||
			cp == 0x10AE6 ||
			(cp >= 0x10D24 && cp <= 0x10D27) ||
			(cp >= 0x10D69 && cp <= 0x10D6D) ||
			cp == 0x10EAB ||
			cp == 0x10EAC ||
			(cp >= 0x10EFA && cp <= 0x10EFF) ||
			(cp >= 0x10F46 && cp <= 0x10F50) ||
			(cp >= 0x10F82 && cp <= 0x10F85) ||
			cp == 0x11001 ||
			(cp >= 0x11038 && cp <= 0x11046) ||
			cp == 0x11070 ||
			cp == 0x11073 ||
			cp == 0x11074 ||
			(cp >= 0x1107F && cp <= 0x11081) ||
			(cp >= 0x110B3 && cp <= 0x110B6) ||
			cp == 0x110B9 ||
			cp == 0x110BA ||
			cp == 0x110C2 ||
			(cp >= 0x11100 && cp <= 0x11102) ||
			(cp >= 0x11127 && cp <= 0x1112B) ||
			(cp >= 0x1112D && cp <= 0x11132) ||
			cp == 0x11134 ||
			cp == 0x11173 ||
			cp == 0x11180 ||
			cp == 0x11181 ||
			(cp >= 0x111B6 && cp <= 0x111BE) ||
			cp == 0x111C0 ||
			(cp >= 0x111C9 && cp <= 0x111CC) ||
			cp == 0x111CF ||
			(cp >= 0x1122F && cp <= 0x11231) ||
			(cp >= 0x11234 && cp <= 0x11237) ||
			cp == 0x1123E ||
			cp == 0x11241 ||
			cp == 0x112DF ||
			(cp >= 0x112E3 && cp <= 0x112EA) ||
			cp == 0x11300 ||
			cp == 0x11301 ||
			cp == 0x1133B ||
			cp == 0x1133C ||
			cp == 0x1133E ||
			cp == 0x11340 ||
			cp == 0x1134D ||
			cp == 0x11357 ||
			(cp >= 0x11366 && cp <= 0x1136C) ||
			(cp >= 0x11370 && cp <= 0x11374) ||
			cp == 0x113B8 ||
			(cp >= 0x113BB && cp <= 0x113C0) ||
			cp == 0x113C2 ||
			cp == 0x113C5 ||
			(cp >= 0x113C7 && cp <= 0x113C9) ||
			cp == 0x113CE ||
			cp == 0x113CF ||
			cp == 0x113D2 ||
			cp == 0x113E1 ||
			cp == 0x113E2 ||
			(cp >= 0x11438 && cp <= 0x1143F) ||
			(cp >= 0x11442 && cp <= 0x11444) ||
			cp == 0x11446 ||
			cp == 0x1145E ||
			cp == 0x114B0 ||
			(cp >= 0x114B3 && cp <= 0x114B8) ||
			cp == 0x114BA ||
			cp == 0x114BD ||
			cp == 0x114BF ||
			cp == 0x114C0 ||
			cp == 0x114C2 ||
			cp == 0x114C3 ||
			cp == 0x115AF ||
			(cp >= 0x115B2 && cp <= 0x115B5) ||
			cp == 0x115BC ||
			cp == 0x115BD ||
			cp == 0x115BF ||
			cp == 0x115C0 ||
			cp == 0x115DC ||
			cp == 0x115DD ||
			(cp >= 0x11633 && cp <= 0x1163A) ||
			cp == 0x1163D ||
			cp == 0x1163F ||
			cp == 0x11640 ||
			cp == 0x116AB ||
			cp == 0x116AD ||
			(cp >= 0x116B0 && cp <= 0x116B7) ||
			cp == 0x1171D ||
			cp == 0x1171F ||
			(cp >= 0x11722 && cp <= 0x11725) ||
			(cp >= 0x11727 && cp <= 0x1172B) ||
			(cp >= 0x1182F && cp <= 0x11837) ||
			cp == 0x11839 ||
			cp == 0x1183A ||
			cp == 0x11930 ||
			(cp >= 0x1193B && cp <= 0x1193D) ||
			cp == 0x11943 ||
			(cp >= 0x119D4 && cp <= 0x119D7) ||
			cp == 0x119DA ||
			cp == 0x119DB ||
			cp == 0x119E0 ||
			(cp >= 0x11A01 && cp <= 0x11A0A) ||
			(cp >= 0x11A33 && cp <= 0x11A38) ||
			(cp >= 0x11A3B && cp <= 0x11A3E) ||
			(cp >= 0x11A51 && cp <= 0x11A56) ||
			(cp >= 0x11A59 && cp <= 0x11A5B) ||
			(cp >= 0x11A8A && cp <= 0x11A96) ||
			cp == 0x11A98 ||
			cp == 0x11B60 ||
			(cp >= 0x11B62 && cp <= 0x11B64) ||
			cp == 0x11B66 ||
			(cp >= 0x11C30 && cp <= 0x11C36) ||
			(cp >= 0x11C38 && cp <= 0x11C3D) ||
			cp == 0x11C3F ||
			(cp >= 0x11C92 && cp <= 0x11CA7) ||
			(cp >= 0x11CAA && cp <= 0x11CB0) ||
			cp == 0x11CB2 ||
			cp == 0x11CB3 ||
			cp == 0x11CB5 ||
			cp == 0x11CB6 ||
			(cp >= 0x11D31 && cp <= 0x11D36) ||
			cp == 0x11D3A ||
			cp == 0x11D3C ||
			cp == 0x11D3D ||
			(cp >= 0x11D3F && cp <= 0x11D45) ||
			cp == 0x11D47 ||
			cp == 0x11D90 ||
			cp == 0x11D91 ||
			cp == 0x11D95 ||
			cp == 0x11D97 ||
			cp == 0x11EF3 ||
			cp == 0x11EF4 ||
			cp == 0x11F00 ||
			cp == 0x11F01 ||
			(cp >= 0x11F36 && cp <= 0x11F3A) ||
			cp == 0x11F40 ||
			cp == 0x11F41 ||
			cp == 0x11F5A ||
			cp == 0x13440 ||
			(cp >= 0x13447 && cp <= 0x13455) ||
			(cp >= 0x1611E && cp <= 0x16129) ||
			(cp >= 0x1612D && cp <= 0x1612F) ||
			(cp >= 0x16AF0 && cp <= 0x16AF4) ||
			(cp >= 0x16B30 && cp <= 0x16B36) ||
			cp == 0x16F4F ||
			(cp >= 0x16F8F && cp <= 0x16F92) ||
			cp == 0x16FE4 ||
			cp == 0x16FF0 ||
			cp == 0x16FF1 ||
			cp == 0x1BC9D ||
			cp == 0x1BC9E ||
			(cp >= 0x1CF00 && cp <= 0x1CF2D) ||
			(cp >= 0x1CF30 && cp <= 0x1CF46) ||
			(cp >= 0x1D165 && cp <= 0x1D169) ||
			(cp >= 0x1D16D && cp <= 0x1D172) ||
			(cp >= 0x1D17B && cp <= 0x1D182) ||
			(cp >= 0x1D185 && cp <= 0x1D18B) ||
			(cp >= 0x1D1AA && cp <= 0x1D1AD) ||
			(cp >= 0x1D242 && cp <= 0x1D244) ||
			(cp >= 0x1DA00 && cp <= 0x1DA36) ||
			(cp >= 0x1DA3B && cp <= 0x1DA6C) ||
			cp == 0x1DA75 ||
			cp == 0x1DA84 ||
			(cp >= 0x1DA9B && cp <= 0x1DA9F) ||
			(cp >= 0x1DAA1 && cp <= 0x1DAAF) ||
			(cp >= 0x1E000 && cp <= 0x1E006) ||
			(cp >= 0x1E008 && cp <= 0x1E018) ||
			(cp >= 0x1E01B && cp <= 0x1E021) ||
			cp == 0x1E023 ||
			cp == 0x1E024 ||
			(cp >= 0x1E026 && cp <= 0x1E02A) ||
			cp == 0x1E08F ||
			(cp >= 0x1E130 && cp <= 0x1E136) ||
			cp == 0x1E2AE ||
			(cp >= 0x1E2EC && cp <= 0x1E2EF) ||
			(cp >= 0x1E4EC && cp <= 0x1E4EF) ||
			cp == 0x1E5EE ||
			cp == 0x1E5EF ||
			cp == 0x1E6E3 ||
			cp == 0x1E6E6 ||
			cp == 0x1E6EE ||
			cp == 0x1E6EF ||
			cp == 0x1E6F5 ||
			(cp >= 0x1E8D0 && cp <= 0x1E8D6) ||
			(cp >= 0x1E944 && cp <= 0x1E94A) ||
			(cp >= 0x1F3FB && cp <= 0x1F3FF) ||
			(cp >= 0xE0020 && cp <= 0xE007F) ||
			(cp >= 0xE0100 && cp <= 0xE01EF);
	}

	private static boolean isConsonant(int cp) {
		if (cp < 0x0900) {
			return false;
		}
		return
			(cp >= 0x0915 && cp <= 0x0939) ||
			(cp >= 0x0958 && cp <= 0x095F) ||
			(cp >= 0x0978 && cp <= 0x097F) ||
			(cp >= 0x0995 && cp <= 0x09A8) ||
			(cp >= 0x09AA && cp <= 0x09B0) ||
			cp == 0x09B2 ||
			(cp >= 0x09B6 && cp <= 0x09B9) ||
			cp == 0x09DC ||
			cp == 0x09DD ||
			cp == 0x09DF ||
			cp == 0x09F0 ||
			cp == 0x09F1 ||
			(cp >= 0x0A95 && cp <= 0x0AA8) ||
			(cp >= 0x0AAA && cp <= 0x0AB0) ||
			cp == 0x0AB2 ||
			cp == 0x0AB3 ||
			(cp >= 0x0AB5 && cp <= 0x0AB9) ||
			cp == 0x0AF9 ||
			(cp >= 0x0B15 && cp <= 0x0B28) ||
			(cp >= 0x0B2A && cp <= 0x0B30) ||
			cp == 0x0B32 ||
			cp == 0x0B33 ||
			(cp >= 0x0B35 && cp <= 0x0B39) ||
			cp == 0x0B5C ||
			cp == 0x0B5D ||
			cp == 0x0B5F ||
			cp == 0x0B71 ||
			(cp >= 0x0C15 && cp <= 0x0C28) ||
			(cp >= 0x0C2A && cp <= 0x0C39) ||
			(cp >= 0x0C58 && cp <= 0x0C5A) ||
			(cp >= 0x0D15 && cp <= 0x0D3A) ||
			(cp >= 0x1000 && cp <= 0x102A) ||
			cp == 0x103F ||
			(cp >= 0x1050 && cp <= 0x1055) ||
			(cp >= 0x105A && cp <= 0x105D) ||
			cp == 0x1061 ||
			cp == 0x1065 ||
			cp == 0x1066 ||
			(cp >= 0x106E && cp <= 0x1070) ||
			(cp >= 0x1075 && cp <= 0x1081) ||
			cp == 0x108E ||
			(cp >= 0x1780 && cp <= 0x17B3) ||
			(cp >= 0x1A20 && cp <= 0x1A54) ||
			cp == 0x1B0B ||
			cp == 0x1B0C ||
			(cp >= 0x1B13 && cp <= 0x1B33) ||
			(cp >= 0x1B45 && cp <= 0x1B4C) ||
			(cp >= 0x1B83 && cp <= 0x1BA0) ||
			cp == 0x1BAE ||
			cp == 0x1BAF ||
			(cp >= 0x1BBB && cp <= 0x1BBD) ||
			(cp >= 0xA989 && cp <= 0xA98B) ||
			(cp >= 0xA98F && cp <= 0xA9B2) ||
			(cp >= 0xA9E0 && cp <= 0xA9E4) ||
			(cp >= 0xA9E7 && cp <= 0xA9EF) ||
			(cp >= 0xA9FA && cp <= 0xA9FE) ||
			(cp >= 0xAA60 && cp <= 0xAA6F) ||
			(cp >= 0xAA71 && cp <= 0xAA73) ||
			cp == 0xAA7A ||
			cp == 0xAA7E ||
			cp == 0xAA7F ||
			(cp >= 0xAAE0 && cp <= 0xAAEA) ||
			(cp >= 0xABC0 && cp <= 0xABDA) ||
			cp == 0x10A00 ||
			(cp >= 0x10A10 && cp <= 0x10A13) ||
			(cp >= 0x10A15 && cp <= 0x10A17) ||
			(cp >= 0x10A19 && cp <= 0x10A35) ||
			(cp >= 0x11103 && cp <= 0x11126) ||
			cp == 0x11144 ||
			cp == 0x11147 ||
			(cp >= 0x11380 && cp <= 0x11389) ||
			cp == 0x1138B ||
			cp == 0x1138E ||
			(cp >= 0x11390 && cp <= 0x113B5) ||
			(cp >= 0x11900 && cp <= 0x11906) ||
			cp == 0x11909 ||
			(cp >= 0x1190C && cp <= 0x11913) ||
			cp == 0x11915 ||
			cp == 0x11916 ||
			(cp >= 0x11918 && cp <= 0x1192F) ||
			cp == 0x11A00 ||
			(cp >= 0x11A0B && cp <= 0x11A32) ||
			cp == 0x11A50 ||
			(cp >= 0x11A5C && cp <= 0x11A83) ||
			(cp >= 0x11F04 && cp <= 0x11F10) ||
			(cp >= 0x11F12 && cp <= 0x11F33);
	}
}
