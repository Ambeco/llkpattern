package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.Matcher;



interface ZeroWidthAssertionGuard {
	boolean holdsHere(Matcher matcher, int peeked);
}
