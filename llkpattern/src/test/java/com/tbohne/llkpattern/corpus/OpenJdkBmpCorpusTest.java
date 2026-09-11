package com.tbohne.llkpattern.corpus;

import java.nio.file.Paths;
import java.util.List;
import org.junit.runners.Parameterized.Parameters;

/**
 * Differential test against OpenJDK's own {@code BMPTestCases.txt} regex test data (BMP-only
 * inputs; see {@link OpenJdkSupplementaryCorpusTest} for the non-BMP/surrogate-pair cousin).
 * Golden file generated via {@code ./tools/scrape_openjdk_regex.py bmp} +
 * {@code ./gradlew :llkpattern:generateCorpus -Punescape=openjdk} -- see CorpusGenerator's javadoc
 * and documents/remaining_work.md for the full pipeline and how to regenerate.
 */
public class OpenJdkBmpCorpusTest extends ScrapedCorpusTestBase {
  public OpenJdkBmpCorpusTest(GoldenRow row) {
    super(row);
  }

  @Parameters(name = "{0}")
  public static List<GoldenRow> data() {
    return readGolden(
        Paths.get("src", "test", "resources", "golden", "openjdk_bmp.tsv"));
  }
}
