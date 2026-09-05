package com.tbohne.oldllkpattern;


import com.tbohne.llkpattern.CharacterClass.SimpleCharacterClass;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

abstract class PatternConstruct {
	final int startIndex;
	int endIndex = -1;
	SimpleCharacterClass preCondition = null;
	SimpleCharacterClass postCondition = null;

	PatternConstruct(int startIndex) {
		this.startIndex = startIndex;
	}

	PatternConstruct(int startIndex, int endIndex) {
		this.startIndex = startIndex;
		this.endIndex = endIndex;
	}

	abstract boolean distinctFromPrecondition(SimpleCharacterClass preCondition);
	abstract boolean distinctFromPostCondition(SimpleCharacterClass postCondition);

	static abstract class QuantifiableConstruct extends PatternConstruct {
		int min = 1;
		int max = 1;

		QuantifiableConstruct(int startIndex) {
			super(startIndex);
		}

		QuantifiableConstruct(int startIndex, int endIndex) {
			super(startIndex, endIndex);
		}
	}

	static class QuantifiedUnion extends QuantifiableConstruct {
		final int parentFlags;

		boolean capturing = true;
		String captureName = "";
		final List<PatternConstruct> patterns = new ArrayList<>();
		boolean tempFlags = false;

		QuantifiedUnion(int startIndex, int parentFlags) {
			super(startIndex);
			this.parentFlags = parentFlags;
		}

		@Override
		boolean distinctFromPrecondition(SimpleCharacterClass preCondition) {
			for (int i=0; i<patterns.size(); i++) {
				if (!patterns.get(i).distinctFromPrecondition(preCondition)) {
					return false;
				}
			}
			return true;
		}

		@Override
		boolean distinctFromPostCondition(SimpleCharacterClass postCondition) {
			for (int i=0; i<patterns.size(); i++) {
				if (!patterns.get(i).distinctFromPostCondition(postCondition)) {
					return false;
				}
			}
			return true;
		}
	}

	static class Sequence extends PatternConstruct {
		final List<PatternConstruct> patterns = new ArrayList<>();

		Sequence(int startIndex) {
			super(startIndex);
		}

		@Override
		boolean distinctFromPrecondition(SimpleCharacterClass preCondition) {
			return patterns.get(0).distinctFromPrecondition(preCondition);
		}

		@Override
		boolean distinctFromPostCondition(SimpleCharacterClass postCondition) {
			return patterns.get(patterns.size()-1).distinctFromPostCondition(postCondition);
		}
	}

	static class LiteralString extends PatternConstruct {
		final String value;
		final CodePointDetails firstCodePoint;
		final CodePointDetails lastCodePoint;

		LiteralString(int startIndex, int endIndex, String value) {
			super(startIndex, endIndex);
			this.value = value;
			firstCodePoint = new CodePointDetails(value.codePointAt(0));
			char lastByte = value.charAt(value.length()-1);
			int lastCp = Character.isLowSurrogate(lastByte) ? value.codePointAt(value.length()-2) : lastByte;
			if (lastCp == firstCodePoint.value) {
				lastCodePoint = firstCodePoint;
			} else {
				lastCodePoint = new CodePointDetails(lastCp);
			}
		}

		@Override
		boolean distinctFromPrecondition(SimpleCharacterClass preCondition) {
			return !preCondition.matches(firstCodePoint);
		}

		@Override
		boolean distinctFromPostCondition(SimpleCharacterClass postCondition) {
			return !postCondition.matches(lastCodePoint);
		}
	}

	static class BackReference extends PatternConstruct {
		final @Nullable Integer id;
		final @Nullable String name;

		BackReference(int startIndex, int endIndex, @Nullable Integer id, @Nullable String name) {
			super(startIndex, endIndex);
			this.id = id;
			this.name = name;
		}

		@Override
		boolean distinctFromPrecondition(SimpleCharacterClass preCondition) {
			return true; //no-op
		}

		@Override
		boolean distinctFromPostCondition(SimpleCharacterClass postCondition) {
			return true; //no-op
		}
	}

	static class ComplexQuantifiedCharacter extends QuantifiableConstruct {
		final SimpleCharacterClass delegate;

		ComplexQuantifiedCharacter(int startIndex, SimpleCharacterClass delegate) {
			super(startIndex, delegate.endIndex);
			this.delegate = delegate;
		}

		@Override
		boolean distinctFromPrecondition(SimpleCharacterClass preCondition) {
			return delegate.distinctFrom(preCondition);
		}

		@Override
		boolean distinctFromPostCondition(SimpleCharacterClass postCondition) {
			return delegate.distinctFrom(postCondition);
		}
	}

	static class BoundaryConstruct extends PatternConstruct {
		enum BoundaryEnum {
			LineBegin,
			LineEnd,
			Word,
			NonWord,
			InputBegin,
			PreviousMatchEnd,
			InputEndExceptTerminator,
			InputEnd,
			Linebreak
		}

		final BoundaryEnum type;

		BoundaryConstruct(int startIndex, int endIndex, BoundaryEnum type) {
			super(startIndex, endIndex);
			this.type = type;
		}

		@Override
		boolean distinctFromPrecondition(SimpleCharacterClass preCondition) {
			return true; // no-op
		}

		@Override
		boolean distinctFromPostCondition(SimpleCharacterClass postCondition) {
			return true; // no-op
		}
	}
}
