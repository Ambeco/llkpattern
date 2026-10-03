package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;

public final class EndConstruct extends PatternConstruct {
	// A true cross-compile singleton, not one-per-Ll1Pattern.compile() call: every field this
	// class ever touches is fixed at construction time and never subsequently written --
	// `flags`/`dispatchEntrySet`/`dispatchFailedEntry` stay at their class defaults (nothing ever
	// assigns them, since this construct never appears as a buildFlattenedChain candidate or a
	// parsed node the parser stamps flags onto), `next` is never assigned (compile()'s own
	// `matcher != null` guard -- already true the moment this constructor returns -- short-
	// circuits before the `this.next = next` line ever runs), and `matcher`/`entryElse` are set
	// once, right here, to values that don't depend on which pattern is being compiled. Nothing
	// reads `startIndex`/`endIndex` back out for this construct either (it's never a chain
	// candidate, so it never appears in an ambiguity error message). Sharing one instance (with
	// its own already-built EndMatcherConstruct, likewise shared) across every compiled pattern
	// removes a real, if small, per-compile allocation pair.
	public static final EndConstruct INSTANCE = new EndConstruct();

	/** The pattern's terminal has no successor: like MatcherConstruct's own `next = this`, it is its own. */
	@Override
	PatternConstruct next() {
		return this;
	}

	private EndConstruct() {
		super(-1);
		// "The pattern's grammar is satisfied here" -- reachable regardless of what character (or
		// lack of one) comes next, matching ANY of them via entryElse rather than only registering
		// the -1 "no more input" sentinel (see Matcher#peek()). That distinction matters for a
		// loop's "should I exit" dispatch (built by merging its body's entry ranges with `next`'s,
		// same as any other branch choice): a plain "-1 only" registration made an optional loop's
		// exit path unreachable at any position with real leftover characters -- which is exactly
		// what lookingAt()/find() need (a matched prefix with more string after it), as opposed to
		// matches() (which needs the *whole region* consumed). Both are supported by the same
		// compiled graph: EndMatcherConstruct.match() enforces the stricter check only when
		// Matcher#requireFullMatch says to -- see its doc.
		entryElse = this;
		new EndMatcherConstruct(this);
	}

	@Override
	boolean elseIsEndOfFind() {
		return true;
	}

	@Override
	protected void buildEntryMap(PatternConstruct next) {
		// An EndConstruct has no "next" -- it's the sentinel marking the end of the whole pattern.
		// entryMap is populated in the constructor (compile() never reaches here -- its `matcher
		// != null` guard short-circuits immediately, since the constructor above also sets
		// `matcher`), but is written this way for anyone reading buildEntryMap for its own sake.
	}

	@Override
	protected void buildMatcher() {
		// matcher is already set by the constructor -- compile() never reaches this (see its
		// `if (matcher == null)` guard) but it's implemented for completeness/symmetry.
	}
}
