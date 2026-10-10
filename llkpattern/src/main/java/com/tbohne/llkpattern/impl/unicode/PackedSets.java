package com.tbohne.llkpattern.impl.unicode;

/**
 * Decodes the packed code point set data that {@code UnicodeAnalyzer} writes into {@code UnicodePredicates}.
 *
 * <p>The data is two ASCII strings (a class-file string constant is capped at 65,535 bytes, so the key stream may
 * come in several pieces that are simply concatenated). Both use the same varint: each char is {@code '#' + v}
 * with {@code v} in 0..63, whose low 5 bits are payload (least significant group first) and whose bit 5 says
 * another char follows. {@code lengths} holds one varint per set: its entry count. {@code keys} holds, per entry
 * of each set in order, a varint gap ({@code min} minus the previous entry's exclusive max, or minus 0 for the
 * set's first entry) and a varint count ({@code ArrayCodePointSet}'s packed {@code count}, i.e. length - 1).
 */
final class PackedSets {
  private PackedSets() {}

  private static final char DIGIT_BASE = '#';
  private static final int PAYLOAD_BITS = 5;
  private static final int PAYLOAD_MASK = (1 << PAYLOAD_BITS) - 1;
  private static final int CONTINUE = 1 << PAYLOAD_BITS;

  static CodePointSet[] decode(String lengths, String... keyPieces) {
    StringBuilder joined = new StringBuilder();
    for (String piece : keyPieces) {
      joined.append(piece);
    }
    String keys = joined.toString();
    int[] keyPos = {0};
    int[] lengthPos = {0};
    int setCount = 0;
    for (int p = 0; p < lengths.length(); p++) {
      if (((lengths.charAt(p) - DIGIT_BASE) & CONTINUE) == 0) {
        setCount++;
      }
    }
    CodePointSet[] sets = new CodePointSet[setCount];
    for (int i = 0; i < setCount; i++) {
      int[] entries = new int[readVarint(lengths, lengthPos)];
      int prevMax = 0;
      for (int e = 0; e < entries.length; e++) {
        int min = prevMax + readVarint(keys, keyPos);
        int count = readVarint(keys, keyPos);
        entries[e] = ArrayCodePointSet.packKey(min, count);
        prevMax = min + count + 1;
      }
      sets[i] = ArrayCodePointSet.ofPackedKeys(entries);
    }
    if (keyPos[0] != keys.length()) {
      throw new IllegalStateException("Packed key data has " + (keys.length() - keyPos[0]) + " trailing chars after "
          + setCount + " sets; the 'lengths' string and the key pieces are out of sync, so regenerate "
          + "UnicodePredicates with UnicodeAnalyzer.");
    }
    return sets;
  }

  private static int readVarint(String data, int[] pos) {
    int value = 0;
    int shift = 0;
    while (true) {
      if (pos[0] >= data.length()) {
        throw new IllegalStateException("Packed data ended mid-value at char " + pos[0] + " of " + data.length()
            + "; did a key piece get truncated or dropped? Regenerate UnicodePredicates with UnicodeAnalyzer.");
      }
      int v = data.charAt(pos[0]++) - DIGIT_BASE;
      if (v < 0 || v > (CONTINUE | PAYLOAD_MASK)) {
        throw new IllegalStateException("Invalid packed digit U+" + Integer.toHexString(v + DIGIT_BASE) + " at char "
            + (pos[0] - 1) + ": expected a char in '#'..'b'.");
      }
      value |= (v & PAYLOAD_MASK) << shift;
      if ((v & CONTINUE) == 0) {
        return value;
      }
      shift += PAYLOAD_BITS;
    }
  }
}
