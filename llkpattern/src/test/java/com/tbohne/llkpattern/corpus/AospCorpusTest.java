package com.tbohne.llkpattern.corpus;

import java.nio.file.Paths;
import java.util.List;
import org.junit.runners.Parameterized.Parameters;

/**
 * Differential test against the rows of AOSP libcore's {@code ojluni} copy of OpenJDK's regex test
 * data that {@link OpenJdkBmpCorpusTest}/{@link OpenJdkSupplementaryCorpusTest} don't already cover
 * (mostly {@code TestCases.txt}, the original ASCII set). Golden file generated via
 * {@code ./tools/scrape_aosp_regex.py} + {@code ./gradlew :llkpattern:generateCorpus
 * -Punescape=openjdk} -- see that script's docstring for the exact commands.
 */
public class AospCorpusTest extends ScrapedCorpusTestBase {
  public AospCorpusTest(GoldenRow row) {
    super(row);
  }

  @Parameters(name = "{0}")
  public static List<GoldenRow> data() {
    return readGolden(
        Paths.get("src", "test", "resources", "golden", "aosp.tsv"));
  }
}
