package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

public final class EndPatternConstruct extends PatternConstruct {
	// A cross-compile singleton: nothing it touches is ever written after construction (flags and
	// dispatch fields keep their defaults, `next` is never assigned because compile()'s
	// `matcher != null` guard short-circuits, matcher/entryElse are set here and are
	// pattern-independent), and it never appears in an ambiguity message. Sharing it with its
	// EndMatcherConstruct saves a per-compile allocation pair.
	public static final EndPatternConstruct INSTANCE = new EndPatternConstruct();

	// No successor: like MatcherConstruct's own `next = this`, it is its own.
	@Override
	PatternConstruct next() {
		return this;
	}

	private EndPatternConstruct() {
		super(-1);
		// Matches ANY next character via entryElse, not just the -1 "no more input" sentinel: a
		// -1-only registration made an optional loop's exit unreachable wherever real characters
		// remain, which lookingAt()/find() need. EndMatcherConstruct enforces the full-region check
		// only when Matcher#requireFullMatch is set.
		entryElse = this;
		new EndMatcherConstruct(this);
	}

	@Override
	boolean elseIsEndOfFind() {
		return true;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		// Unreachable: compile() short-circuits because the constructor already set matcher.
	}

	@Override
	void buildMatcher() {
		// Unreachable: matcher is already set by the constructor.
	}
}
