package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import com.tbohne.llkpattern.MatcherConstruct.EndMatcherConstruct;
import com.tbohne.llkpattern.MatcherConstruct.ForkingMatcherConstruct;
import com.tbohne.llkpattern.MatcherConstruct.LiteralMatcherConstruct;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PatternParserTest {

	// Three distinct supplementary (astral) code points (U+10000/1/2, DESERET LONG A/AH/ES),
	// standing in for the plain 'a'/'b'/'c' letters these tests used to use -- see
	// SupplementaryPatternTextTest for why exercising the parser's own lookahead across a
	// multi-code-unit character matters, on top of what these tests already check structurally.
	private static final String A = "𐀀";
	private static final String B = "𐀁";
	private static final String C = "𐀂";

	private static MatcherConstruct compile(String pattern) {
		return Ll1Pattern.compile(pattern).compiled;
	}

	@Test
	public void compile_singleLetter_isLiteralPattern() {
		MatcherConstruct compiled = compile(A);

		assertThat(compiled, instanceOf(LiteralMatcherConstruct.class));
		// .value is a CharSequence, not necessarily a String (see its own doc) -- toString() to
		// compare content rather than relying on CharSequence's own (implementation-specific, often
		// type-mismatched) equals().
		assertThat(((LiteralMatcherConstruct) compiled).value.toString(), is(A));
		// A literal is Single-dispatching -- what comes after the *whole* literal is an unconditional
		// forward (next), not something keyed by its own first character.
		assertThat(((LiteralMatcherConstruct) compiled).getNext(), instanceOf(EndMatcherConstruct.class));
	}

	@Test
	public void compile_multiLetterLiteral_isSingleLiteralMatcher() {
		// "AB" parses as one LiteralString (raw text accumulation), not two chained matchers.
		MatcherConstruct compiled = compile(A + B);

		assertThat(compiled, instanceOf(LiteralMatcherConstruct.class));
		assertThat(((LiteralMatcherConstruct) compiled).value.toString(), is(A + B));
		assertThat(((LiteralMatcherConstruct) compiled).getNext(), instanceOf(EndMatcherConstruct.class));
	}

	@Test
	public void match_singleLetter_actuallyMatches() {
		// Unlike the structural assertions above, this exercises match() end-to-end -- exactly what
		// caught the elseDispatch-vs-dispatchMap bug the other two tests' structural checks missed.
		assertThat(Ll1Pattern.compile(A).matcher(A).matches(), is(true));
	}

	@Test
	public void match_multiLetterLiteral_actuallyMatches() {
		assertThat(Ll1Pattern.compile(A + B).matcher(A + B).matches(), is(true));
	}

	@Test
	public void match_literalFollowedByLiteral_actuallyMatches() {
		// A Sequence of two distinct LiteralMatcherConstructs (not merged raw text) -- exercises
		// the first literal's elseDispatch handing off correctly to the second.
		assertThat(Ll1Pattern.compile("[" + A + "]" + B).matcher(A + B).matches(), is(true));
	}

	@Test
	public void compile_sequenceOfCharacterClasses_chainsToNext() {
		// [A][B] is two separate ComplexQuantifiedCharacter nodes (not merged raw text), so this
		// exercises Sequence's tail-to-front chaining between two distinct matcher nodes. Each
		// SingleCharMatcherConstruct is Single-dispatching -- a character class has exactly one
		// successor regardless of which member character was seen -- so chaining is via `.getNext()`.
		MatcherConstruct compiled = compile("[" + A + "][" + B + "]");

		assertThat(compiled, instanceOf(MatcherConstruct.SingleCharMatcherConstruct.class));
		MatcherConstruct second = ((MatcherConstruct.SingleCharMatcherConstruct) compiled).getNext();
		assertThat(second, instanceOf(MatcherConstruct.SingleCharMatcherConstruct.class));
		assertThat(((MatcherConstruct.SingleCharMatcherConstruct) second).getNext(), instanceOf(EndMatcherConstruct.class));
	}

	@Test
	public void compile_alternation_dispatchesToEachBranch() {
		// "A|B" (no catch-all branch) compiles to just ONE fork: on A -> literal A, else -> the
		// literal B matcher directly, unconditionally -- the second (last, catch-all-less) branch
		// needs no wrapping fork of its own, since its own compiled matcher already re-verifies
		// membership as its first action -- see MatcherConstruct.ForkingMatcherConstruct's own doc.
		MatcherConstruct compiled = compile(A + "|" + B);

		assertThat(compiled, instanceOf(ForkingMatcherConstruct.class));
		ForkingMatcherConstruct firstFork = (ForkingMatcherConstruct) compiled;
		assertThat(firstFork.getNext(), instanceOf(LiteralMatcherConstruct.class));
		assertThat(firstFork.getOtherwise(), instanceOf(LiteralMatcherConstruct.class));
	}

	@Test
	public void compile_ambiguousAlternation_throwsPatternSyntaxException() {
		// Both branches start with A -- this is exactly the LL(1) restriction this engine exists
		// to enforce: which branch to take must be decidable from the next character alone.
		assertThrows(java.util.regex.PatternSyntaxException.class, () -> compile(A + B + "|" + A + C));
	}
}
