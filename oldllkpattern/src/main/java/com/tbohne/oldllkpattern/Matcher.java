package com.tbohne.oldllkpattern;

import java.util.regex.MatchResult;

/**
 * An engine that performs match operations on a character sequence by
 * interpreting an Ll1Pattern.
 *
 * <p>This is very similar to {@link java.util.regex.Matcher}, except that it
 * requires an Ll1Pattern input:
 * <ul>
 *   <li>The first character of each branch of a choice ("|", aka alternation, aka
 *   set-union) must have a strictly distinct pattern.</li>
 *   <li>The first character after any quantifier ("?" or "*" or "+" or "{n,m}")
 *   must have a strictly distinct pattern than the start of the qualifier.</li>
 *   <li>(?idmsuxU) and (?idmsux:...) cannot be used to turn flags on and off</li>
 * </ul>
 * These restrictions make writing a regex more annoying, but the pattern can
 * be both compiled and matched in linear time. FAR faster than a full regex.
 * Note that since this restriction would effectively make "*" useless, this code
 * simply makes it match everything that would otherwise be valid.
 */
public class Matcher implements MatchResult {
	public static String quoteReplacement(String s) {
		throw new UnsupportedOperationException("TODO: implement Matcher#quoteReplacement");
	}

	private Ll1Pattern pattern;
	private String input;
	private int regionEnd;
	private int regionStart = 0;
	private int pos = 0;

	Matcher(Ll1Pattern pattern, String input) {
		this.pattern = pattern;
		this.input = input;
		this.regionEnd = input.length();
	}

	public Matcher appendReplacement(StringBuffer sb, String replacement) {
		throw new UnsupportedOperationException("TODO: implement Matcher#appendReplacement");
	}

	public StringBuffer appendTail(StringBuffer sb) {
		throw new UnsupportedOperationException("TODO: implement Matcher#appendTail");
	}

	public int end() {
		throw new UnsupportedOperationException("TODO: implement Matcher#end");
	}

	public int end(int group) {
		throw new UnsupportedOperationException("TODO: implement Matcher#end");
	}

	public int end(String name) {
		throw new UnsupportedOperationException("TODO: implement Matcher#end");
	}

	public boolean find() {
		throw new UnsupportedOperationException("TODO: implement Matcher#find");
	}

	public boolean find(int start) {
		throw new UnsupportedOperationException("TODO: implement Matcher#find");
	}

	public String group() {
		throw new UnsupportedOperationException("TODO: implement Matcher#group");
	}

	public String group(int group) {
		throw new UnsupportedOperationException("TODO: implement Matcher#group");
	}

	public String group(String group) {
		throw new UnsupportedOperationException("TODO: implement Matcher#group");
	}

	public int groupCount()  {
		throw new UnsupportedOperationException("TODO: implement Matcher#groupCount");
	}

	public boolean hasAnchoringBounds()  {
		throw new UnsupportedOperationException("TODO: implement Matcher#hasAnchoringBounds");
	}

	public boolean hasTransparentBounds()  {
		throw new UnsupportedOperationException("TODO: implement Matcher#hasTransparentBounds");
	}

	public boolean hitEnd() {
		throw new UnsupportedOperationException("TODO: implement Matcher#hitEnd");
	}

	public boolean lookingAt() {
		throw new UnsupportedOperationException("TODO: implement Matcher#lookingAt");
	}

	public boolean matches() {
		throw new UnsupportedOperationException("TODO: implement Matcher#matches");
	}

	public Ll1Pattern pattern() {
		return pattern;
	}

	public Matcher region(int start, int end)  {
		this.regionStart = start;
		this.regionEnd = end;
		return this;
	}

	public int regionEnd()  {
		return regionEnd;
	}

	public int regionStart()  {
		return regionStart;
	}

	String replaceAll(String replacement) {
		throw new UnsupportedOperationException("TODO: implement Matcher#replaceAll");
	}

	String replaceFirst(String replacement) {
		throw new UnsupportedOperationException("TODO: implement Matcher#replaceFirst");
	}

	boolean requireEnd() {
		throw new UnsupportedOperationException("TODO: implement Matcher#requireEnd");
	}

	public Matcher reset() {
		regionStart = 0;
		regionEnd = input.length();
		pos = 0;
		return this;
	}

	public Matcher reset(String input)  {
		this.input = input;
		regionStart = 0;
		regionEnd = input.length();
		pos = 0;
		return this;
	}

	public int start() {
		throw new UnsupportedOperationException("TODO: implement Matcher#start");
	}

	public int start(int group)  {
		throw new UnsupportedOperationException("TODO: implement Matcher#start");
	}

	public int start(String name)  {
		throw new UnsupportedOperationException("TODO: implement Matcher#start");
	}

	public MatchResult toMatchResult() {
		throw new UnsupportedOperationException("TODO: implement Matcher#toMatchResult");
	}

	public String toString()  {
		return pattern.toString();
	}

	public Matcher useAnchoringBounds(boolean b)  {
		throw new UnsupportedOperationException("TODO: implement Matcher#useAnchoringBounds");
	}

	public Matcher usePattern(Ll1Pattern newPattern)  {
		if (pattern == null) {
			throw new IllegalArgumentException("newPattern cannot be null");
		}
		pattern = newPattern;
		return this;
	}

	public Matcher useTransparentBounds(boolean b)  {
		throw new UnsupportedOperationException("TODO: implement Matcher#useTransparentBounds");
	}

	int peek() {
		return input.codePointAt(pos);
	}

	boolean consumeLiteral(String value) {
		if (pos + value.length() >= input.length()) {
			return false;
		}
		if (!input.startsWith(value, pos)) {
			return false;
		}
		pos += value.length();
		return true;
	}

	void consume1CodePoint() {
		char codeunit = input.charAt(pos);
		pos += (codeunit<=0xDFF||codeunit>=0xE000 ? 1 : 2);
	}

	int beginCapture(String name) {
		throw new UnsupportedOperationException("TODO: implement Matcher#beginCapture");
	}

	void endCapture(int captureId, boolean result) {
		throw new UnsupportedOperationException("TODO: implement Matcher#endCapture");
	}
}
