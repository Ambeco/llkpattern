package com.tbohne.oldllkpattern;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public class CodePointDetails implements Comparable<CodePointDetails> {
	public final int value;
	private @MonotonicNonNull String stringCache;
	private Character.@MonotonicNonNull UnicodeScript scriptCache;
	private Character.@MonotonicNonNull UnicodeBlock blockCache;
	private @MonotonicNonNull Byte categoryCache;

	public CodePointDetails(int codePoint) {
		this.value = codePoint;
	}

	public int value() {
		return value;
	}

	public String string() {
		if (stringCache == null) {
			stringCache = new String(Character.toChars(value));
		}
		return stringCache;
	}

	public Character.UnicodeScript script() {
		if (scriptCache == null) {
			scriptCache = Character.UnicodeScript.of(value);
		}
		return scriptCache;
	}

	public Character.UnicodeBlock block() {
		if (blockCache == null) {
			blockCache = Character.UnicodeBlock.of(value);
		}
		return blockCache;
	}

	public byte category() {
		if (categoryCache == null) {
			categoryCache = (byte) Character.getType(value);
		}
		return categoryCache;
	}

	@Override
	public int compareTo(CodePointDetails other) {
		return value - other.value;
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (!(other instanceof CodePointDetails)) {
			return false;
		}
		CodePointDetails rhs = (CodePointDetails) other;
		return value == rhs.value;
	}

	@Override
	public int hashCode() {
		return value;
	}

	@Override
	public String toString() {
		return string();
	}

	public static class CharRange implements Comparable<CharRange> {
		final CodePointDetails minCodePoint;
		final CodePointDetails maxCodePoint;

		CharRange(int minCodePoint, int maxCodePoint) {
			if (minCodePoint >= maxCodePoint) {
				throw new IllegalArgumentException("minCodePoint " + minCodePoint + " is not less than maxCodePoint " + maxCodePoint);
			}
      this.minCodePoint = new CodePointDetails(minCodePoint);
			this.maxCodePoint = new CodePointDetails(minCodePoint);
		}

    boolean matches(int codePoint) {
      return minCodePoint.value <= codePoint && maxCodePoint.value >= codePoint;
    }

    @Override
    public int compareTo(CharRange other) {
      if (minCodePoint.value != other.minCodePoint.value)
        return minCodePoint.value - other.minCodePoint.value;
      if (maxCodePoint.value != other.maxCodePoint.value)
        return maxCodePoint.value - other.maxCodePoint.value;
      return 0;
    }
  }
}
