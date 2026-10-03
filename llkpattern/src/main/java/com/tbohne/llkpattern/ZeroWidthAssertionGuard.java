package com.tbohne.llkpattern;



interface ZeroWidthAssertionGuard {
	boolean holdsHere(Matcher matcher, int peeked);
}
