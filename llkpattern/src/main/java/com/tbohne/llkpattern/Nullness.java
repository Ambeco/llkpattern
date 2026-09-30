package com.tbohne.llkpattern;

import org.checkerframework.checker.nullness.qual.Nullable;

/** Nullness-checker helpers that avoid a runtime dependency on the Checker Framework's util jar. */
final class Nullness {
  private Nullness() {}

  /** Asserts to the nullness checker that {@code value} is non-null where an invariant it cannot see
   *  guarantees so; does nothing at runtime. */
  @SuppressWarnings("nullness")
  static <T> T castNonNull(@Nullable T value) {
    return value;
  }
}
