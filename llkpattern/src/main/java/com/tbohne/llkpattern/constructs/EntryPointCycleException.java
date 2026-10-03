package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;

public final class EntryPointCycleException extends RuntimeException {
	public final int startIndex;

	EntryPointCycleException(int startIndex) {
		this.startIndex = startIndex;
	}
}
