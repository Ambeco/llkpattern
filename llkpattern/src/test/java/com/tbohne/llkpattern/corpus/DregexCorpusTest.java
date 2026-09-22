package com.tbohne.llkpattern.corpus;

import java.nio.file.Paths;
import java.util.List;
import org.junit.runners.Parameterized.Parameters;

/**
 * Differential test against dregex's own regex test data (its {@code MatchTest} table of {@code
 * Regex.compile(pattern)} blocks, each followed by one or more {@code r.matches(input)} calls --
 * heavy on lookaround, since that is dregex's own headline feature). Golden file generated via
 * {@code ./tools/scrape_dregex.py} + {@code ./gradlew :llkpattern:generateCorpus -Pmode=MATCHES
 * -Punescape=tsv} -- see that script's docstring for the exact commands.
 */
public class DregexCorpusTest extends ScrapedCorpusTestBase {
  public DregexCorpusTest(GoldenRow row) {
    super(row);
  }

  @Parameters(name = "{0}")
  public static List<GoldenRow> data() {
    return readGolden(
        Paths.get("src", "test", "resources", "golden", "dregex.tsv"));
  }
}
