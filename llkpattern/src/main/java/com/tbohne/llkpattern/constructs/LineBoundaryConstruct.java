package com.tbohne.llkpattern.constructs;

import com.tbohne.llkpattern.*;

import com.tbohne.llkpattern.NamedCharClass.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class LineBoundaryConstruct extends ZeroWidthAssertionConstruct {
	public final boolean isLineBegin; // true: ^, false: $

	public LineBoundaryConstruct(int startIndex, int endIndex, boolean isLineBegin) {
		super(startIndex, endIndex);
		this.isLineBegin = isLineBegin;
	}

	@Override
	protected void buildMatcher() {
		new LineBoundaryMatcherConstruct(this, isLineBegin);
	}

	/**
	 * Only ever contributes anything under {@code MULTILINE} (a non-MULTILINE ^/$ only ever
	 * holds at the true input edges, never at an interior loop-exit position). {@code $} holds
	 * whenever the PEEK character itself is a line terminator, regardless of what the loop
	 * body's last-consumed character was, so its admitted set is exactly the terminator-starting
	 * code points, unconditionally. {@code ^} holds whenever the PRIOR character was a line
	 * terminator, regardless of peek, so its admitted set is "any code point" whenever the body
	 * could plausibly have just consumed one, and empty (no interior exit possible via ^)
	 * otherwise; returns {@code null} ("not statically known") when {@code bodyLastCharSet}
	 * itself is {@code null}, same safe fallback {@code WordBoundaryConstruct}'s own version
	 * uses.
	 */
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

	/**
	 * The code points that can BEGIN a line terminator (matching {@code MatcherConstruct}'s own
	 * runtime {@code lineTerminatorLengthAt}/{@code lineTerminatorLengthBefore} scans, honoring
	 * {@code UNIX_LINES}) -- sufficient for a single-code-point admitted-peek-set check, since
	 * every terminator this engine recognizes ({@code \n}, {@code \r}, {@code "\r\n"} as one
	 * unit, {@code \u0085}, {@code  }, {@code  }) is uniquely identified by its own
	 * first code point.
	 */
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
