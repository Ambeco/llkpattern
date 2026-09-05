package com.tbohne.oldllkpattern;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public abstract class CharacterClass {
  final int startIndex;
  int endIndex;

  CharacterClass(int startIndex) {
    this.startIndex = startIndex;
  }

  static public class SimpleCharacterClass extends CharacterClass {
      boolean positive = true;
      final StringBuilder directMatches;
      final List<CodePointDetails.CharRange> ranges;
      final List<IntPredicate> characterPredicates; // java.lang.Character#isX(int)
      final List<Character.UnicodeScript> scripts;
      final List<Character.UnicodeBlock> blocks;
      final List<Byte> categories; // java.lang.Character#getType(int)

      @MonotonicNonNull List<CodePointDetails> directDetailsCache;

    SimpleCharacterClass() {
      super(/* startIndex=*/ -1);
        directMatches = new StringBuilder();
        ranges = new ArrayList<>();
        characterPredicates = new ArrayList<>();
        scripts = new ArrayList<>();
        blocks = new ArrayList<>();
        categories = new ArrayList<>();
      }

    SimpleCharacterClass(int startIndex) {
      super(startIndex);
        directMatches = new StringBuilder();
        ranges = new ArrayList<>();
        characterPredicates = new ArrayList<>();
        scripts = new ArrayList<>();
        blocks = new ArrayList<>();
        categories = new ArrayList<>();
      }

    SimpleCharacterClass(SimpleCharacterClass deepCopyFrom) {
        this(deepCopyFrom.startIndex);
        endIndex = deepCopyFrom.endIndex;
        add(deepCopyFrom);
      }

      private SimpleCharacterClass(boolean positive, SimpleCharacterClass shallowCopyFrom) {
        super(shallowCopyFrom.startIndex);
        endIndex = shallowCopyFrom.endIndex;
        this.positive = positive ^ shallowCopyFrom.positive;
        directMatches = shallowCopyFrom.directMatches;
        ranges = shallowCopyFrom.ranges;
        characterPredicates = shallowCopyFrom.characterPredicates;
        scripts = shallowCopyFrom.scripts;
        blocks = shallowCopyFrom.blocks;
        categories = shallowCopyFrom.categories;
      }

      SimpleCharacterClass shallowCloneNegate() {
        return new SimpleCharacterClass(false, this);
      }

      List<CodePointDetails> getDirectDetails() {
        if (directDetailsCache == null) {
          directDetailsCache = new ArrayList<>(directMatches.length());
          for (int i = 0; i < directMatches.length(); i++) {
            int cp = directMatches.codePointAt(i);
            directDetailsCache.add(new CodePointDetails(cp));
            if (cp > Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
              i++;
            }
          }
        }
        return directDetailsCache;
      }

      SimpleCharacterClass add(int codePoint) {
        directMatches.appendCodePoint(codePoint);
        return this;
      }

      SimpleCharacterClass add(String directMatches) {
        this.directMatches.append(directMatches);
        return this;
      }

      SimpleCharacterClass add(CodePointDetails.CharRange range) {
        ranges.add(range);
        return this;
      }

      SimpleCharacterClass add(IntPredicate characterPredicate) {
        characterPredicates.add(characterPredicate);
        return this;
      }

      SimpleCharacterClass add(Character.UnicodeScript script) {
        scripts.add(script);
        return this;
      }

      SimpleCharacterClass add(Character.UnicodeBlock block) {
        blocks.add(block);
        return this;
      }

      SimpleCharacterClass add(byte category) {
        categories.add(category);
        return this;
      }

      SimpleCharacterClass add(List<Byte> categories) {
        this.categories.addAll(categories);
        return this;
      }

      SimpleCharacterClass add(SimpleCharacterClass other) {
        if (other == null) {
          return this;
        }
        positive = other.positive;
        directMatches.append(other.directMatches);
        ranges.addAll(other.ranges);
        characterPredicates.addAll(other.characterPredicates);
        scripts.addAll(other.scripts);
        blocks.addAll(other.blocks);
        categories.addAll(other.categories);
        return this;
      }

      public boolean matches(CodePointDetails codePoint) {
        boolean contained = directMatches.indexOf(codePoint.string()) >= 0;
        if (contained) {
          return positive;
        }
        for (int i = 0; i < ranges.size(); i++) {
          if (ranges.get(i).matches(codePoint.value)) {
            return positive;
          }
        }
        if (scripts.contains(codePoint.script())
            || blocks.contains(codePoint.block())
            || categories.contains(codePoint.category())) {
          return positive;
        }
        for (int i = 0; i < characterPredicates.size(); i++) {
          if (characterPredicates.get(i).test(codePoint.value)) {
            return positive;
          }
        }
        return !positive;
      }

      public boolean distinctFrom(SimpleCharacterClass other) {
        boolean distinct = distinctNonIntersection(other);
        if (!distinct) {
          return false;
        }
        for (int i = 0; i < intersections.size(); i++) {
          if (!intersections.get(i).distinctFrom(other)) {
            return false;
          }
        }
        return true;
      }

      public boolean distinctNonIntersection(SimpleCharacterClass other) {
        List<CodePointDetails> directDetails = getDirectDetails();
        for (int i = 0; i < directDetails.size(); i++) {
          if (other.matches(directDetails.get(i))) {
            return false;
          }
        }
        for (int i = 0; i < ranges.size(); i++) {
          if (!other.probablyDistinct(ranges.get(i))) {
            return false;
          }
        }
        for (int i = 0; i < scripts.size(); i++) {
          if (!other.probablyDistinct(scripts.get(i))) {
            return false;
          }
        }
        for (int i = 0; i < blocks.size(); i++) {
          if (!other.probablyDistinct(blocks.get(i))) {
            return false;
          }
        }
        for (int i = 0; i < categories.size(); i++) {
          if (!other.probablyDistinct(categories.get(i))) {
            return false;
          }
        }
        for (int i = 0; i < characterPredicates.size(); i++) {
          if (!other.probablyDistinct(categories.get(i))) {
            return false;
          }
        }
        return true;
      }
  }

  public static class IntersectionCharacterClass extends CharacterClass {

  }
}
