package com.tbohne.llkpattern;

class PatternSyntaxException extends java.util.regex.PatternSyntaxException {

	static class CodePoint {
		final int codePoint;

		CodePoint(int codePoint) {
			this.codePoint = codePoint;
		}
	}

	static class CodePointReference {
		final int index;

		CodePointReference(int index) {
			this.index = index;
		}
	}

	static class Reference {
		int startIndex;
		int endIndex;

		Reference(int startIndex, int endIndex) {
			this.startIndex = startIndex;
			this.endIndex = endIndex;
		}
	}

	public PatternSyntaxException(String desc, String regex, int index) {
		super(desc, regex, index);
	}

	private static StringBuilder appendCodePoint(StringBuilder sb, int codePoint) {
		if (codePoint < 0) {
			return sb.append("end of pattern");
		}
		sb.append("'");
		// Not sb.appendCodePoint(codePoint) -- the JDK's own implementation allocates a throwaway
		// char[2] (via Character.toChars) for any supplementary code point just to copy it into sb
		// right after. This method only runs while building an exception message (cold path, never
		// once per parsed character the way PatternParser's own copy of this fix is), so the
		// allocation wouldn't matter here on its own, but there's no reason to pay for it either.
		if (Character.isBmpCodePoint(codePoint)) {
			sb.append((char) codePoint);
		} else {
			sb.append(Character.highSurrogate(codePoint)).append(Character.lowSurrogate(codePoint));
		}
		return sb.append("' (U+")
						 .append(String.format("%04x", codePoint))
						 .append(")");
	}

	private static void appendReference(StringBuilder sb, String pattern, int index, int codePoint) {
		int contextStart = Math.max(index - 6, 0);
		int contextEnd = Math.min(index + 6, pattern.length());
		sb.append("  [");
		appendCodePoint(sb, codePoint).append(" at index ")
																	.append(index)
																	.append(" near \"")
																	.append(pattern, contextStart, contextEnd)
																	.append("\"]\n");
	}

	static PatternSyntaxException throwWithReferences(String pattern, int index, Object... expectations) {
		StringBuilder msg = new StringBuilder();
		StringBuilder context = new StringBuilder();
		for (int i = 0; i < expectations.length; i++) {
			Object o = expectations[i];
			if (o instanceof CodePoint) {
				appendCodePoint(msg, ((CodePoint)o).codePoint);
			} else if (o instanceof CodePointReference) {
				int codePointIndex = ((CodePointReference)o).index;
				// A reference at/after the end of the pattern (e.g. "a|(" or "(?<") has no code point.
				int codePoint = codePointIndex < pattern.length() ? pattern.codePointAt(codePointIndex) : -1;
				appendCodePoint(msg, codePoint);
				appendReference(context, pattern, codePointIndex, codePoint);
			} else {
				msg.append(expectations[i]);
			}
		}
		msg.append('\n').append(context);
		throw new PatternSyntaxException(msg.toString(), pattern, index);
	}
}
