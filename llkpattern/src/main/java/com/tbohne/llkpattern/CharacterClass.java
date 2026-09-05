package com.tbohne.llkpattern;

import com.google.common.collect.ImmutableRangeSet;

public abstract class CharacterClass {
  ImmutableRangeSet<Integer> ranges;

  CharacterClass(int startIndex, ImmutableRangeSet<Integer> ranges) {
    this.ranges = ranges;
  }

  public boolean matches(int codePoint) {
    return ranges.contains(codePoint);
  }

  public boolean distinctFrom(CharacterClass other) {
    return ranges.intersection(other.ranges).isEmpty();
  }
}
