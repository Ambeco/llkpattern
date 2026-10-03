package com.tbohne.llkpattern;

import com.tbohne.llkpattern.NamedCharClass.*;

final class CaptureEndMarker extends PatternConstruct {
	final int captureConstructIndex;
	final PatternConstruct realNext;

	CaptureEndMarker(int startIndex, int captureConstructIndex, PatternConstruct realNext) {
		super(startIndex);
		this.captureConstructIndex = captureConstructIndex;
		this.realNext = realNext;
	}

	/** Never wired by a parent: it is constructed knowing its successor. */
	@Override
	PatternConstruct next() {
		return realNext;
	}

	@Override
	boolean claimsEntryElse() {
		// realNext is a fixed field (unlike `next`, never reassigned to point back at some
		// ancestor mid-construction), so delegating straight through can't participate in the
		// one cycle this engine actually has (a nullable loop body) -- safe to bypass this
		// marker's own cycle guard entirely, same reasoning as its buildEntryMap override.
		return realNext.claimsEntryElse();
	}

	@Override
	boolean elseIsEndOfFind() {
		return realNext.elseIsEndOfFind();
	}

	@Override
	boolean elseIsResidual() {
		return realNext.elseIsResidual();
	}

	@Override
	boolean needsEntryPointBeforeMatcher() {
		// buildMatcher() below reads only realNext.matcher -- nothing buildEntryMap() sets.
		return false;
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		// realNext is already compiled by the time any of this marker's callers need it -- it's
		// the capturing group's own `next`, which (like any `next`) was compiled before the group
		// itself, tail-to-front.
		//
		// Bug fix (2026-09-06): this used to just alias `entryMap = realNext.entryMap` directly
		// -- but that leaked every entry's VALUE as realNext itself (whatever realNext.buildEntryMap
		// put there -- entryMap was PatternConstruct-valued at the time), not this marker. That
		// silently broke identity checks like fork chain's loop-flavored
		// constructor's `e.getValue() == next` (used to tell "the loop is exiting toward `next`"
		// from "the loop is continuing") whenever THIS marker was passed in as that `next` -- i.e.
		// any non-quantified capturing group whose content contains its own internal loop, e.g.
		// "([a-z]+)!": the exit character got misclassified as "continue the loop, dispatch
		// straight to realNext.matcher", bypassing this marker's own EndCaptureMatcherConstruct
		// entirely, so the capture's `result` was set on entry but never finalized (group(n)
		// returned null even though the whole pattern matched). Found via GroupSyntaxTest.
		//
		// entryMap has since been migrated to Boolean-only values (see its own doc) specifically
		// because that value "carries zero information" -- so aliasing is safe again now, and
		// re-keying (copying) is back to being pure wasted work: every consumer of THIS marker's
		// entryMap only ever asks "which code points are in it", never anything realNext-specific,
		// so sharing realNext's own (also always-Boolean-`true`) map changes nothing observable.
		// Any identity check this bug was about reads a construct's own PatternConstruct-valued
		// candidate list (e.g. `constructs`/`rawEntryElse`) or mergeEntryPoints' own transient
		// merge (used only to run its ambiguity check), never this plain, Boolean-only entryMap.
		entryMap = realNext.getEntryPointMap();
		if (realNext.getEntryElse() != null) {
			entryElse = this;
		}
	}

	@Override
	void buildMatcher() {
		new EndCaptureMatcherConstruct(this, captureConstructIndex, realNext.matcher());
	}
}
