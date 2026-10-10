package com.tbohne.llkpattern.impl.unicode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Covers {@link PackedSets}' varint format (digit char = '#' + 5 payload bits + a continue bit at 32, least
 * significant group first) with hand-encoded data, and the structural invariants of the generated sets.
 */
@RunWith(JUnit4.class)
public class PackedSetsTest {
  @Test
  public void decodesGapCountPairsAcrossPieces() {
    // set 0: [0x41,0x5B) [0x61,0x7B); set 1: empty. 0x41 = 65 = "D%", 25 = '<', gap 6 = ')'.
    CodePointSet[] sets = PackedSets.decode("%#", "D%<", ")<");
    assertEquals(2, sets.length);
    assertTrue(sets[0].contains('A'));
    assertTrue(sets[0].contains('Z'));
    assertFalse(sets[0].contains('['));
    assertTrue(sets[0].contains('a'));
    assertTrue(sets[0].contains('z'));
    assertFalse(sets[0].contains('{'));
    assertFalse(sets[0].contains('@'));
    assertTrue(sets[1].isEmpty());
  }

  @Test
  public void longRunDecodesToTheSameSetAppendSortedBuilds() {
    // [0, 0x1000) is two full chunks: gap 0 / count 2047 ("bb$": 31, 31, 1), twice.
    CodePointSet[] sets = PackedSets.decode("%", "#bb$#bb$");
    ArrayCodePointSet expected = new ArrayCodePointSet();
    expected.appendSorted(0, 0x1000);
    assertEquals(expected, sets[0]);
    assertTrue(sets[0].contains(0xFFF));
    assertFalse(sets[0].contains(0x1000));
  }

  @Test
  public void truncatedDataNamesTheProblem() {
    try {
      PackedSets.decode("$", "D");
      fail("expected IllegalStateException");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("ended mid-value"));
      assertTrue(e.getMessage(), e.getMessage().contains("Regenerate"));
    }
  }

  @Test
  public void trailingDataNamesTheProblem() {
    try {
      PackedSets.decode("$", "#<#<");
      fail("expected IllegalStateException");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("trailing"));
    }
  }

  @Test
  public void invalidDigitNamesTheProblem() {
    try {
      PackedSets.decode("$", "~<");
      fail("expected IllegalStateException");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("Invalid packed digit"));
    }
  }

  @Test
  public void generatedSetsAreWellFormed() throws Exception {
    int checked = 0;
    for (Field f : UnicodePredicates.class.getDeclaredFields()) {
      if (!CodePointSet.class.isAssignableFrom(f.getType()) || !Modifier.isStatic(f.getModifiers())) {
        continue;
      }
      f.setAccessible(true);
      ArrayCodePointSet set = (ArrayCodePointSet) f.get(null);
      assertFalse(f.getName(), set.invert);
      int prevMax = -1;
      for (int i = 0; i < set.size; i++) {
        int min = ArrayCodePointSet.keyMin(set.keys[i]);
        int max = ArrayCodePointSet.keyMax(set.keys[i]);
        // A run split into chunks is the one case where entries touch.
        assertTrue(f.getName() + " entry " + i + " overlaps its predecessor", min >= prevMax);
        assertTrue(f.getName() + " entry " + i + " is empty", max > min);
        assertTrue(f.getName() + " entry " + i + " is past U+10FFFF", max <= 0x110000);
        prevMax = max;
      }
      checked++;
    }
    assertTrue("expected hundreds of generated sets, found " + checked, checked > 500);
  }

  @Test
  public void spotChecksOfGeneratedSets() {
    assertTrue(UnicodePredicates.ascii.contains(0x7F));
    assertFalse(UnicodePredicates.ascii.contains(0x80));
    assertTrue(UnicodePredicates.isLetter.contains('x'));
    assertFalse(UnicodePredicates.isLetter.contains('7'));
    assertTrue(UnicodePredicates.isDigit.contains('7'));
    assertTrue(UnicodePredicates.isValidCodePoint.contains(0x10FFFF));
    assertFalse(UnicodePredicates.isValidCodePoint.contains(0x110000));
  }
}
