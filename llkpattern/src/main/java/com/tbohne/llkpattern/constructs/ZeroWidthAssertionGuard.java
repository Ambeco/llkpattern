package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;



interface ZeroWidthAssertionGuard {
	boolean holdsHere(Matcher matcher, int peeked);
}
