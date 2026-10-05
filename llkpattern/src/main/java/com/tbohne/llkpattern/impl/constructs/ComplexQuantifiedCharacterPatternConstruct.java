package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class ComplexQuantifiedCharacterPatternConstruct extends QuantifiablePatternConstruct {
	final ComplexCharacterPatternConstruct delegate;
	// Built once: List.of would allocate an ArrayList plus a wrapper per call on Android.
	private final ConstructList delegateBody;

	public ComplexQuantifiedCharacterPatternConstruct(String pattern, int startIndex, ComplexCharacterPatternConstruct delegate) {
		super(pattern, startIndex, delegate.endIndex);
		this.delegate = delegate;
		this.delegateBody = ConstructList.of(delegate);
	}

	@Override
	boolean claimsEntryElse() {
		if (!isUnquantified()) {
			// Real dispatch case: must use the cycle-guarded path, since next may loop back here (a nullable
			// body like [ab]{0,2}).
			return super.claimsEntryElse();
		}
		// Unquantified: buildEntryMap sets entryElse only for a residual (`.`) delegate.
		return delegate.residualElse;
	}

	@Override
	boolean elseIsEndOfFind() {
		return !isUnquantified() && loopElseIsEndOfFind(delegateBody, next());
	}

	@Override
	boolean elseIsResidual() {
		return isUnquantified() ? delegate.residualElse : loopElseIsResidual(delegateBody, next());
	}

	@Override
	void buildEntryMap(PatternConstruct next) {
		if (!isUnquantified()) {
			buildLoopEntryMap(delegateBody, next, -1);
			return;
		}
		// Unquantified: the entry set is the delegate's own ranges (aliased), whatever follows; no compile needed.
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
			buildLoopMatcher(delegateBody, next(), -1);
			return;
		}
		// Unquantified: behaves exactly like the delegate, so pass our dispatch fields to it BEFORE compiling;
		// safe because it is exclusively ours.
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
