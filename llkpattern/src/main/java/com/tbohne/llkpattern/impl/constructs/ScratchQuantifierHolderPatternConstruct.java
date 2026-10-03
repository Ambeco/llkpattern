package com.tbohne.llkpattern.impl.constructs;

/**
 * A throwaway {@link QuantifiablePatternConstruct} that only holds the {@code min}/{@code max} a
 * parser reads off a quantifier suffix after a zero-width construct ({@code ^ $ \B \A \Z \z}); it
 * is never added to the parsed pattern, so it is never asked for an entry map or a matcher.
 */
public final class ScratchQuantifierHolderPatternConstruct extends QuantifiablePatternConstruct {
	public ScratchQuantifierHolderPatternConstruct(String pattern, int startIndex) {
		super(pattern, startIndex);
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		throw new UnsupportedOperationException("ScratchQuantifierHolderPatternConstruct is never compiled");
	}

	@Override
	void buildMatcher() {
		throw new UnsupportedOperationException("ScratchQuantifierHolderPatternConstruct is never compiled");
	}
}
