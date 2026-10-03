package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;

public final class EntryPointCycleException extends RuntimeException {
	public final int startIndex;

	EntryPointCycleException(int startIndex) {
		this.startIndex = startIndex;
	}
}
