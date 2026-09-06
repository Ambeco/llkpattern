package com.tbohne.llkpattern.corpus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads/writes {@link GoldenRow} lists as tab-separated golden files.
 *
 * <p>Field escaping: within a field, a backslash, tab, CR and LF are escaped as backslash-backslash,
 * backslash-t, backslash-r, backslash-n respectively (Java-string-literal style, but only those
 * four characters -- everything else, including raw Unicode code points, passes through
 * unescaped). This is deliberately independent of the JDK regex corpus's own escaping convention
 * (backslash-u-XXXX / backslash-n; see documents/tools/scrape_openjdk_regex.py): that convention
 * is unescaped once, when scraping, into real Java strings, and GoldenTsv's escaping is applied
 * fresh on top when serializing those strings to a row.
 */
public final class GoldenTsv {
  private static final String HEADER =
      "pattern\tflags\tinput\tmode\tregexCompileException\tregexMatchException\t"
          + "regexMatchResult\tllkCompileException\tllkMatchException\tllkMatchResult\tstatus\t"
          + "originalPathologicalInput";

  private GoldenTsv() {}

  public static String encodeField(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\':
          sb.append("\\\\");
          break;
        case '\t':
          sb.append("\\t");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\n':
          sb.append("\\n");
          break;
        default:
          if (isLoneSurrogate(s, i)) {
            // An unpaired UTF-16 surrogate (e.g. from OpenJDK's SupplementaryTestCases.txt,
            // which deliberately tests Matcher against invalid-but-legal-as-a-char-sequence
            // input -- see CorpusGenerator.unescapeOpenJdk) can't be UTF-8-encoded at all: it's
            // not a valid Unicode scalar value by itself. Escape it as \xHHHH (distinct from the
            // backslash-u-XXXX convention some *source* corpora use, which this class has
            // nothing to do with) so the golden file itself stays plain, valid UTF-8 text.
            sb.append("\\x").append(String.format("%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }

  /** True if any char in {@code s} is a lone (unpaired) UTF-16 surrogate -- see {@link
   *  #isLoneSurrogate(String, int)}. Exposed for {@code MatchRunner}'s input-integrity assertion:
   *  see documents/remaining_work.md's surrogate-matching entry for why that matters. */
  public static boolean containsLoneSurrogate(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (isLoneSurrogate(s, i)) {
        return true;
      }
    }
    return false;
  }

  /** True if the char at {@code index} is a UTF-16 surrogate that is NOT part of a valid
   *  high+low pair with its neighbor -- i.e. one that {@code UTF-8}, which has no way to encode a
   *  lone surrogate, cannot represent on its own. */
  private static boolean isLoneSurrogate(String s, int index) {
    char c = s.charAt(index);
    if (!Character.isSurrogate(c)) {
      return false;
    }
    if (Character.isHighSurrogate(c)) {
      return index + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(index + 1));
    }
    // low surrogate
    return index == 0 || !Character.isHighSurrogate(s.charAt(index - 1));
  }

  public static String decodeField(String s) {
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
                  "Truncated \\x escape in golden TSV field '" + s + "' -- expected 4 hex "
                      + "digits after \\x.");
            }
            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
            i += 4;
            break;
          default:
            throw new IllegalArgumentException(
                "Unknown escape '\\" + next + "' in golden TSV field '" + s + "' -- did you mean "
                    + "\\\\, \\t, \\r, \\n, or \\x (a lone UTF-16 surrogate)? A literal backslash "
                    + "must be doubled.");
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  public static List<GoldenRow> read(Path path) {
    try (BufferedReader r =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8))) {
      return read(r, path.toString());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed reading golden file " + path, e);
    }
  }

  static List<GoldenRow> read(BufferedReader r, String sourceNameForErrors) throws IOException {
    List<GoldenRow> rows = new ArrayList<>();
    String header = r.readLine();
    if (header == null) {
      throw new IllegalArgumentException(
          "Golden file " + sourceNameForErrors + " is empty -- expected a header line: " + HEADER);
    }
    if (!header.equals(HEADER)) {
      throw new IllegalArgumentException(
          "Golden file " + sourceNameForErrors + " has an unexpected header. Expected:\n"
              + HEADER + "\nActual:\n" + header
              + "\n(Did the column set change? Update both the header and every reader/writer.)");
    }
    String line;
    int lineNum = 1;
    while ((line = r.readLine()) != null) {
      lineNum++;
      if (line.isEmpty()) {
        continue;
      }
      String[] rawFields = line.split("\t", -1);
      List<String> fields = new ArrayList<>(rawFields.length);
      for (String raw : rawFields) {
        fields.add(decodeField(raw));
      }
      try {
        rows.add(GoldenRow.fromFields(fields));
      } catch (RuntimeException e) {
        throw new IllegalArgumentException(
            "Failed parsing " + sourceNameForErrors + " line " + lineNum + ": " + e.getMessage(),
            e);
      }
    }
    return rows;
  }

  public static void write(Path path, List<GoldenRow> rows) {
    try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      w.write(HEADER);
      w.write("\n");
      for (GoldenRow row : rows) {
        List<String> fields = row.toFields();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
          if (i > 0) {
            line.append('\t');
          }
          line.append(encodeField(fields.get(i)));
        }
        w.write(line.toString());
        w.write("\n");
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing golden file " + path, e);
    }
  }
}
