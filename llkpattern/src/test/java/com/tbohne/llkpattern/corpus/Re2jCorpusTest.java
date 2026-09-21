package com.tbohne.llkpattern.corpus;

import java.nio.file.Paths;
import java.util.List;
import org.junit.runners.Parameterized.Parameters;

/**
 * Differential test against RE2J's own regex test data (its {@code FindTest} table, the AT&amp;T
 * {@code basic/nullsubexpr/repetition.dat} files and {@code re2-search.txt}). Golden file generated
 * via {@code ./tools/scrape_re2j.py} + {@code ./gradlew :llkpattern:generateCorpus
 * -Punescape=tsv} -- see that script's docstring for the exact commands.
 */
public class Re2jCorpusTest extends ScrapedCorpusTestBase {
  public Re2jCorpusTest(GoldenRow row) {
    super(row);
  }

  @Parameters(name = "{0}")
  public static List<GoldenRow> data() {
    return readGolden(
        Paths.get("src", "test", "resources", "golden", "re2j.tsv"));
  }
}
