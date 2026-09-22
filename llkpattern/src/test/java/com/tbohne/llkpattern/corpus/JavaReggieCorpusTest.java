package com.tbohne.llkpattern.corpus;

import java.nio.file.Paths;
import java.util.List;
import org.junit.runners.Parameterized.Parameters;

/**
 * Differential test against DataDog/java-reggie's regex integration-test data (its RE2 and PCRE
 * {@code pattern;input;should_match;features} text files, plus its {@code common/patterns.json}).
 * Golden file generated via {@code ./tools/scrape_java_reggie.py} + {@code ./gradlew
 * :llkpattern:generateCorpus -Pmode=FIND -Punescape=tsv} -- see that script's docstring for the
 * exact commands (and why its two capturing-group files are deliberately not scraped).
 */
public class JavaReggieCorpusTest extends ScrapedCorpusTestBase {
  public JavaReggieCorpusTest(GoldenRow row) {
    super(row);
  }

  @Parameters(name = "{0}")
  public static List<GoldenRow> data() {
    return readGolden(
        Paths.get("src", "test", "resources", "golden", "java_reggie.tsv"));
  }
}
