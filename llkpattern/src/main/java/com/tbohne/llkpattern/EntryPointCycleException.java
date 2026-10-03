package com.tbohne.llkpattern;

import com.tbohne.llkpattern.NamedCharClass.*;

final class EntryPointCycleException extends RuntimeException {
	final int startIndex;

	EntryPointCycleException(int startIndex) {
		this.startIndex = startIndex;
	}
}
