package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import com.tbohne.llkpattern.MatcherConstruct.DispatchMatcherConstruct;
import com.tbohne.llkpattern.MatcherConstruct.EndMatcherConstruct;
import com.tbohne.llkpattern.MatcherConstruct.LiteralMatcherConstruct;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PatternParserTest {

	private static MatcherConstruct compile(String pattern) {
		PatternConstruct parsed = new PatternParser(pattern, 0).parse();
		return parsed.compile(new PatternConstruct.EndConstruct(parsed.endIndex));
	}

	@Test
	public void compile_singleLetter_isLiteralPattern() {
		MatcherConstruct compiled = compile("a");

		assertThat(compiled, instanceOf(LiteralMatcherConstruct.class));
		assertThat(((LiteralMatcherConstruct) compiled).value, is("a"));
		assertThat(compiled.getDispatchMap().get(+'a'), instanceOf(EndMatcherConstruct.class));
		assertThat(compiled.getElse(), nullValue());
	}

	@Test
	public void compile_multiLetterLiteral_isSingleLiteralMatcher() {
		// "ab" parses as one LiteralString (raw text accumulation), not two chained matchers.
		MatcherConstruct compiled = compile("ab");

		assertThat(compiled, instanceOf(LiteralMatcherConstruct.class));
		assertThat(((LiteralMatcherConstruct) compiled).value, is("ab"));
		assertThat(compiled.getDispatchMap().get(+'a'), instanceOf(EndMatcherConstruct.class));
	}

	@Test
	public void compile_sequenceOfCharacterClasses_chainsToNext() {
		// [a][b] is two separate ComplexQuantifiedCharacter nodes (not merged raw text), so this
		// exercises Sequence's tail-to-front chaining between two distinct matcher nodes.
		MatcherConstruct compiled = compile("[a][b]");

		assertThat(compiled.getDispatchMap().get(+'a'), instanceOf(MatcherConstruct.SingleCharMatcherConstruct.class));
		MatcherConstruct second = compiled.getDispatchMap().get(+'a');
		assertThat(second.getDispatchMap().get(+'b'), instanceOf(EndMatcherConstruct.class));
	}

	@Test
	public void compile_alternation_dispatchesToEachBranch() {
		MatcherConstruct compiled = compile("a|b");

		assertThat(compiled, instanceOf(DispatchMatcherConstruct.class));
		assertThat(compiled.getDispatchMap().get(+'a'), instanceOf(LiteralMatcherConstruct.class));
		assertThat(compiled.getDispatchMap().get(+'b'), instanceOf(LiteralMatcherConstruct.class));
		assertThat(compiled.getDispatchMap().get(+'c'), nullValue());
	}

	@Test
	public void compile_ambiguousAlternation_throwsPatternSyntaxException() {
		// Both branches start with 'a' -- this is exactly the LL(1) restriction this engine exists
		// to enforce: which branch to take must be decidable from the next character alone.
		assertThrows(java.util.regex.PatternSyntaxException.class, () -> compile("ab|ac"));
	}
}
