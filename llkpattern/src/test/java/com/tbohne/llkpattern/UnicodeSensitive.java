package com.tbohne.llkpattern;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test whose outcome depends on Unicode data that {@code UnicodePredicates} pins to JDK
 * 27's tables while the test compares against the running JDK's own. When it fails on a JDK other
 * than 27, {@link UnicodeDriftRule} reports it as skipped (expected drift) rather than failed. The
 * class must declare {@code @Rule public final UnicodeDriftRule unicodeDrift = new
 * UnicodeDriftRule();}. The golden-corpus analogue is the {@code unicodeSensitive} TSV column.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UnicodeSensitive {}
