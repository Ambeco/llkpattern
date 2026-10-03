package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import java.util.List;

public final class ComplexQuantifiedCharacterPatternConstruct extends QuantifiablePatternConstruct {
	final ComplexCharacterPatternConstruct delegate;

	public ComplexQuantifiedCharacterPatternConstruct(String pattern, int startIndex, ComplexCharacterPatternConstruct delegate) {
		super(pattern, startIndex, delegate.endIndex);
		this.delegate = delegate;
	}

	@Override
	boolean claimsEntryElse() {
		if (!isUnquantified()) {
			// Real dispatch/ambiguity-checked case -- must go through the ordinary cycle-guarded
			// path (this construct's own `next` might loop back here, e.g. a nullable body like
			// `[ab]{0,2}` -- see buildLoopEntryMap).
			return super.claimsEntryElse();
		}
		// Unquantified: buildEntryMap sets entryElse only for a residual (`.`) delegate.
		return delegate.residualElse;
	}

	@Override
	boolean elseIsEndOfFind() {
		return !isUnquantified() && loopElseIsEndOfFind(List.of(delegate), next());
	}

	@Override
	boolean elseIsResidual() {
		return isUnquantified() ? delegate.residualElse : loopElseIsResidual(List.of(delegate), next());
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		if (!isUnquantified()) {
			buildLoopEntryMap(List.of(delegate), next, -1);
			return;
		}
		// Unquantified: entry set is exactly the delegate's own ranges, regardless of what
		// follows -- no need for `delegate` to be compiled (matcher-built) yet to know this;
		// that happens in buildMatcher(), below. Aliased directly, same reasoning as
		// ComplexCharacterPatternConstruct.buildEntryMap.
		if (delegate.residualElse) {
			entryMap = EMPTY_ENTRY_MAP;
			entryElse = this;
		} else {
			entryMap = delegate.validRanges();
		}
	}

	@Override
	void buildMatcher() {
		if (!isUnquantified()) {
			buildLoopMatcher(List.of(delegate), next(), -1);
			return;
		}
		// Unquantified (i.e. exactly-once) case: this construct behaves exactly like its
		// delegate ComplexCharacterPatternConstruct -- propagate our own dispatch fields (if we're ourselves a
		// chain candidate) onto `delegate` BEFORE compiling it, so its own compiled node ends up
		// with the right gating; safe because `delegate` is exclusively owned by this construct
		// (created together, never independently compiled from anywhere else).
		delegate.dispatchEntrySet = dispatchEntrySet;
		delegate.dispatchFailedEntry = dispatchFailedEntry;
		delegate.compile(next());
		matcher = delegate.matcher();
	}

	@Override
	final @Nullable CodePointSet lastCharSet() {
		return min >= 1 ? delegate.validRanges() : null;
	}

	@Override
	final @Nullable CodePointSet firstCharSet() {
		return min >= 1 ? delegate.validRanges() : null;
	}

	@Override
	public final LookbehindPatternConstruct.@Nullable SingleCodePointBody resolveSingleCodePointBody() {
		return min == 1 && max == 1
				? new LookbehindPatternConstruct.SingleCodePointBody(delegate.validRanges(), -1)
				: null;
	}
}
