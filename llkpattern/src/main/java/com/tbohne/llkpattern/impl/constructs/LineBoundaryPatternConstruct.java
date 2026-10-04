package com.tbohne.llkpattern.impl.constructs;

import com.tbohne.llkpattern.impl.unicode.CodePointSet;
import com.tbohne.llkpattern.impl.unicode.CodePointSetBuilder;
import com.tbohne.llkpattern.Ll1Pattern;
import com.tbohne.llkpattern.impl.unicode.NamedCharClass;

import com.tbohne.llkpattern.impl.unicode.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * {@code ^} (line begin) / {@code $} (line end). Separate from {@link BoundaryPatternConstruct}
 * because it has its own logic (MULTILINE-aware line-terminator scanning). See design.md's
 * "Boundary matching".
 */
public final class LineBoundaryPatternConstruct extends ZeroWidthAssertionPatternConstruct {
	public final boolean isLineBegin; // true: ^, false: $

	public LineBoundaryPatternConstruct(int startIndex, int endIndex, boolean isLineBegin) {
		super(startIndex, endIndex);
		this.isLineBegin = isLineBegin;
	}

	@Override
	void buildMatcher() {
		new LineBoundaryMatcherConstruct(this, isLineBegin);
	}

	// Contributes only under MULTILINE (otherwise ^/$ hold only at the true input edges). $ holds
	// whenever PEEK is a line terminator, so its set is the terminator starts. ^ holds whenever the
	// PRIOR char was a terminator, so its set is "any" if the body could have just consumed one,
	// else empty. Null if bodyLastCharSet is null.
	@Override
	final @Nullable CodePointSet admittedInteriorExitPeekSet(@Nullable CodePointSet bodyLastCharSet) {
		if ((flags & Ll1Pattern.MULTILINE) == 0) {
			return null;
		}
		CodePointSet terminatorStarts = lineTerminatorStartCodePoints(flags);
		if (!isLineBegin) {
			return terminatorStarts;
		}
		if (bodyLastCharSet == null) {
			return null;
		}
		return bodyLastCharSet.intersects(terminatorStarts) ? universalCodePointSet() : null;
	}

	// Code points that can BEGIN a line terminator (honoring UNIX_LINES): enough here, since every
	// recognized terminator, including "\r\n", is identified by its first code point.
	private static CodePointSet lineTerminatorStartCodePoints(int flags) {
		CodePointSetBuilder result = CodePointSetBuilder.create();
		result.add('\n');
		if ((flags & Ll1Pattern.UNIX_LINES) == 0) {
			result.add('\r');
			result.add(0x0085);
			result.append(0x2028, 0x202A);
		}
		return result.build();
	}
}
