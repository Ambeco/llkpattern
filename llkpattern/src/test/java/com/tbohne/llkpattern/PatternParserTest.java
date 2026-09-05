package com.tbohne.llkpattern;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import com.tbohne.llkpattern.MatcherConstruct.SingleCharMatcherConstruct;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PatternParserTest {

	// TODO(remaining_work.md "core implementation"): PatternConstruct.compile(...) doesn't build a
	// MatcherConstruct graph yet -- see the TODO on PatternConstruct.compile itself. Re-enable once
	// that's implemented; this documents the intended end-to-end behavior in the meantime.
	@Ignore("compile() doesn't build a MatcherConstruct graph yet")
	@Test
	public void compile_singleLetter_isLiteralPattern() {
		PatternParser parser = new PatternParser("a", 0);
		PatternConstruct parsed = parser.parse();
		MatcherConstruct compiled = parsed.compile(new PatternConstruct.EndConstruct(parsed.endIndex));

    assertThat(compiled, instanceOf(SingleCharMatcherConstruct.class));
		assertThat(compiled.getDispatchMap().get(+'a'), instanceOf(PatternConstruct.EndConstruct.class));
    assertThat(compiled.getElse(), nullValue());
	}
}
