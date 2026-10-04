package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

/**
 * Thrown by {@code PatternConstruct.ensureEntryPointBuilt} when a construct's entry point would
 * require its own computation to already be finished: a quantified construct whose body can match
 * zero characters (e.g. {@code (a?)+}). Rethrown as a {@code PatternSyntaxException} by {@code
 * Ll1Pattern.compile()}, which has the pattern string. See design.md's "Entry-point computation
 * vs. matcher compilation".
 */
public final class EntryPointCycleException extends RuntimeException {
	public final int startIndex;

	EntryPointCycleException(int startIndex) {
		this.startIndex = startIndex;
	}
}
