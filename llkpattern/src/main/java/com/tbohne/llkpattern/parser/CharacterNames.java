package com.tbohne.llkpattern.parser;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Unicode character names for {@code \N{name}}, from the running platform.
 *
 * <p>{@code Character.codePointOf} (JDK 9+, and Android from the release whose ICU has it) is the
 * only source: embedding the whole name table would cost far more than this rarely used escape is
 * worth. It is looked up reflectively because this module compiles at source level 8.
 */
final class CharacterNames {
  private static final java.lang.reflect.@Nullable Method CODE_POINT_OF;

  static {
    java.lang.reflect.Method method = null;
    try {
      method = Character.class.getMethod("codePointOf", String.class);
    } catch (NoSuchMethodException e) {
      // reported with a detailed message at the point of use
    }
    CODE_POINT_OF = method;
  }

  private CharacterNames() {}

  static boolean isAvailable() {
    return CODE_POINT_OF != null;
  }

  /** Throws {@link IllegalArgumentException} for an unknown name. */
  // Static-method reflection: Method.invoke takes a null receiver, which the checker's JDK stub rejects.
  @SuppressWarnings("nullness:argument")
  static int codePointOf(String name) {
    java.lang.reflect.Method method = CODE_POINT_OF;
    if (method == null) {
      throw new UnsupportedOperationException(
          "Character.codePointOf is missing on Java " + System.getProperty("java.version")
              + "; check isAvailable() first");
    }
    try {
      return (Integer) castNonNull(method.invoke(null, name));
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw new IllegalArgumentException("Unknown character name \"" + name + "\"", e);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
}
