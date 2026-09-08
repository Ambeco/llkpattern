package com.tbohne.llkpattern.corpus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@link AndroidGoldenRow} lists from the golden TSV assets bundled into this test APK by
 * {@code copyGoldenAssetsForAndroidTest} (app/build.gradle) -- see {@link AndroidGoldenRow}'s
 * javadoc for why this is a standalone reader rather than reusing {@code GoldenTsv} directly.
 *
 * <p>Only the columns {@link AndroidGoldenRow} actually keeps are decoded; the rest of each line
 * is parsed (to keep column indices correct) and discarded.
 */
final class AndroidGoldenTsv {
  private static final String HEADER =
      "pattern\tflags\tinput\tmode\tregexCompileException\tregexMatchException\t"
          + "regexMatchResult\tllkCompileException\tllkMatchException\tllkMatchResult\tstatus\t"
          + "originalPathologicalInput";
  private static final int NUM_FIELDS = 12;

  private AndroidGoldenTsv() {}

  static List<AndroidGoldenRow> read(InputStream in, String sourceNameForErrors) {
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      List<AndroidGoldenRow> rows = new ArrayList<>();
      String header = r.readLine();
      if (header == null) {
        throw new IllegalArgumentException(
            "Golden asset " + sourceNameForErrors + " is empty -- expected a header line: "
                + HEADER);
      }
      if (!header.equals(HEADER)) {
        throw new IllegalArgumentException(
            "Golden asset " + sourceNameForErrors + " has an unexpected header. Expected:\n"
                + HEADER + "\nActual:\n" + header
                + "\n(Did GoldenRow's column set change without updating AndroidGoldenTsv too?)");
      }
      String line;
      int lineNum = 1;
      while ((line = r.readLine()) != null) {
        lineNum++;
        if (line.isEmpty()) {
          continue;
        }
        String[] rawFields = line.split("\t", -1);
        if (rawFields.length != NUM_FIELDS) {
          throw new IllegalArgumentException(
              "Golden asset " + sourceNameForErrors + " line " + lineNum + " has "
                  + rawFields.length + " fields, expected " + NUM_FIELDS + ".");
        }
        AndroidGoldenRow.Mode mode;
        try {
          mode = AndroidGoldenRow.Mode.valueOf(rawFields[3]);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException(
              "Golden asset " + sourceNameForErrors + " line " + lineNum + ": unknown mode '"
                  + rawFields[3] + "'.",
              e);
        }
        rows.add(
            new AndroidGoldenRow(
                decodeField(rawFields[0]),
                decodeField(rawFields[1]),
                decodeField(rawFields[2]),
                mode,
                decodeField(rawFields[4]),
                decodeField(rawFields[7]),
                decodeField(rawFields[10])));
      }
      return rows;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed reading golden asset " + sourceNameForErrors, e);
    }
  }

  /** Mirrors {@code GoldenTsv.decodeField} -- must stay in sync with it. */
  private static String decodeField(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char next = s.charAt(++i);
        switch (next) {
          case '\\':
            sb.append('\\');
            break;
          case 't':
            sb.append('\t');
            break;
          case 'r':
            sb.append('\r');
            break;
          case 'n':
            sb.append('\n');
            break;
          case 'x':
            if (i + 4 >= s.length()) {
              throw new IllegalArgumentException(
                  "Truncated \\x escape in golden TSV field '" + s + "'.");
            }
            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
            i += 4;
            break;
          default:
            throw new IllegalArgumentException(
                "Unknown escape '\\" + next + "' in golden TSV field '" + s + "'.");
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
