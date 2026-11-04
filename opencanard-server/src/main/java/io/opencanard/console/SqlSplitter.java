package io.opencanard.console;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into its statements at the {@code ;} that are not inside a string, a quoted identifier, a
 * dollar-quoted string or a comment. JDK only.
 * <ul>
 * <li>{@code 'text'} with {@code ''} as escaped quote</li>
 * <li>{@code "identifier"} with {@code ""} as escaped quote</li>
 * <li>{@code $$ text $$} and {@code $tag$ text $tag$}</li>
 * <li>{@code -- comment} up to the end of the line, and {@code /* comment *}{@code /}</li>
 * </ul>
 * Statements are trimmed and the terminating {@code ;} is removed. A piece with nothing but whitespace and
 * comments is not a statement and is dropped.
 */
public final class SqlSplitter {

	private SqlSplitter() {
	}

	public static List<String> split(String script) {
		List<String> statements = new ArrayList<>();
		if (script == null) {
			return statements;
		}
		int n = script.length();
		int start = 0;
		boolean hasContent = false;
		int i = 0;
		while (i < n) {
			char c = script.charAt(i);
			if (c == '\'' || c == '"') {
				i = skipQuoted(script, i, c);
				hasContent = true;
			} else if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
				int eol = script.indexOf('\n', i);
				i = eol < 0 ? n : eol + 1;
			} else if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
				int end = script.indexOf("*/", i + 2);
				i = end < 0 ? n : end + 2;
			} else if (c == '$') {
				int end = skipDollarQuoted(script, i);
				if (end > i) {
					i = end;
					hasContent = true;
				} else {
					i++;
					hasContent = true;
				}
			} else if (c == ';') {
				add(statements, script, start, i, hasContent);
				start = i + 1;
				hasContent = false;
				i++;
			} else {
				if (!Character.isWhitespace(c)) {
					hasContent = true;
				}
				i++;
			}
		}
		add(statements, script, start, n, hasContent);
		return statements;
	}

	private static void add(List<String> out, String script, int from, int to, boolean hasContent) {
		if (!hasContent) {
			return;
		}
		String text = script.substring(from, to).trim();
		if (!text.isEmpty()) {
			out.add(text);
		}
	}

	/** Index after the closing quote; a doubled quote inside is an escaped quote. An open quote runs to the end. */
	private static int skipQuoted(String s, int start, char quote) {
		int i = start + 1;
		while (i < s.length()) {
			if (s.charAt(i) == quote) {
				if (i + 1 < s.length() && s.charAt(i + 1) == quote) {
					i += 2;
					continue;
				}
				return i + 1;
			}
			i++;
		}
		return s.length();
	}

	/**
	 * If a dollar quote ({@code $$} or {@code $tag$}) starts at {@code start}, the index after its closing tag,
	 * otherwise {@code start}.
	 */
	private static int skipDollarQuoted(String s, int start) {
		int i = start + 1;
		while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
			i++;
		}
		if (i >= s.length() || s.charAt(i) != '$') {
			return start;
		}
		String tag = s.substring(start, i + 1); // $tag$
		int end = s.indexOf(tag, i + 1);
		return end < 0 ? s.length() : end + tag.length();
	}
}
