package com.tbohne.llkpattern;

public class PatternSyntaxException extends java.util.regex.PatternSyntaxException {

	public static class CodePoint {
		final int codePoint;

		public CodePoint(int codePoint) {
			this.codePoint = codePoint;
		}
	}

	public static class CodePointReference {
		final int index;

		public CodePointReference(int index) {
			this.index = index;
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
		// Not sb.appendCodePoint: it allocates a throwaway char[2] for supplementary code points.
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

	public static PatternSyntaxException throwWithReferences(String pattern, int index, Object... expectations) {
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
