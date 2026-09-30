package com.tbohne.llkpattern;

import com.tbohne.llkpattern.corpus.GoldenRow;
import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/** Turns an {@link AssertionError} in a {@link UnicodeSensitive} test into a skip when the running
 *  JDK isn't the one whose Unicode tables the project is pinned to. Passing tests, and failures on
 *  JDK {@link GoldenRow#UNICODE_DATA_JDK_FEATURE}, are untouched. */
public final class UnicodeDriftRule implements TestRule {
  @Override
  public Statement apply(Statement base, Description description) {
    if (description.getAnnotation(UnicodeSensitive.class) == null) {
      return base;
    }
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        try {
          base.evaluate();
        } catch (AssertionError e) {
          if (GoldenRow.hostUnicodeMatchesGolden()) {
            throw e;
          }
          throw new AssumptionViolatedException(
              "Expected Unicode-version drift under JDK " + Runtime.version().feature()
                  + " (data is pinned to JDK " + GoldenRow.UNICODE_DATA_JDK_FEATURE + "): "
                  + e.getMessage(),
              e);
        }
      }
    };
  }
}
