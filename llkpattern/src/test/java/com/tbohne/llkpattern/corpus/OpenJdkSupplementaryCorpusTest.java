package com.tbohne.llkpattern.corpus;

import java.nio.file.Paths;
import java.util.List;
import org.junit.runners.Parameterized.Parameters;

/**
 * Differential test against OpenJDK's own {@code SupplementaryTestCases.txt} regex test data
 * (non-BMP code points and, deliberately, some unpaired-surrogate inputs; see {@link
 * OpenJdkBmpCorpusTest} for the BMP-only cousin). Golden file generated via {@code
 * documents/tools/scrape_openjdk_regex.py supplementary} + {@code ./gradlew
 * :llkpattern:generateCorpus -Punescape=openjdk} -- see CorpusGenerator's javadoc and
 * documents/remaining_work.md for the full pipeline and how to regenerate.
 */
public class OpenJdkSupplementaryCorpusTest extends ScrapedCorpusTestBase {
  public OpenJdkSupplementaryCorpusTest(GoldenRow row) {
    super(row);
  }

  @Parameters(name = "{0}")
  public static List<GoldenRow> data() {
    return readGolden(
        Paths.get("src", "test", "resources", "golden", "openjdk_supplementary.tsv"));
  }
}
