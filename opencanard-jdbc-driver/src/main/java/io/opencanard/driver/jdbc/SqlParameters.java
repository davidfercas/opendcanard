package io.opencanard.driver.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Client-side parameter binding for prepared statements. The Flight server receives plain SQL, so the
 * driver replaces each {@code ?} by a SQL literal. JDK only.
 * <p>
 * A {@code ?} inside a string literal ({@code '...'}), a quoted identifier ({@code "..."}) or a comment
 * is not a parameter.
 */
final class SqlParameters {

	private final List<String> parts = new ArrayList<>(); // text between the parameters
	private Object[] values;
	private boolean[] isSet;

	SqlParameters(String sql) {
		if (sql == null) {
			throw new IllegalArgumentException("SQL is null");
		}
		StringBuilder current = new StringBuilder();
		int n = sql.length();
		for (int i = 0; i < n; i++) {
			char c = sql.charAt(i);
			if (c == '\'' || c == '"') {
				int end = skipQuoted(sql, i, c);
				current.append(sql, i, end);
				i = end - 1;
			} else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
				int end = sql.indexOf('\n', i);
				end = end < 0 ? n : end;
				current.append(sql, i, end);
				i = end - 1;
			} else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
				int end = sql.indexOf("*/", i + 2);
				end = end < 0 ? n : end + 2;
				current.append(sql, i, end);
				i = end - 1;
			} else if (c == '?') {
				parts.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		parts.add(current.toString());
		values = new Object[parts.size() - 1];
		isSet = new boolean[values.length];
	}

	/** Index after the closing quote; a doubled quote inside is an escaped quote. */
	private static int skipQuoted(String sql, int start, char quote) {
		int i = start + 1;
		while (i < sql.length()) {
			if (sql.charAt(i) == quote) {
				if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
					i += 2;
					continue;
				}
				return i + 1;
			}
			i++;
		}
		return sql.length();
	}

	int count() {
		return values.length;
	}

	void set(int index, Object value) throws SQLException {
		if (index < 1 || index > values.length) {
			throw new SQLException("Parameter index " + index + " is out of range, the statement has "
					+ values.length + " parameter(s)");
		}
		values[index - 1] = value;
		isSet[index - 1] = true;
	}

	void clear() {
		Arrays.fill(values, null);
		Arrays.fill(isSet, false);
	}

	/** The SQL with every {@code ?} replaced by its literal. */
	String bind() throws SQLException {
		StringBuilder sb = new StringBuilder(parts.get(0));
		for (int i = 0; i < values.length; i++) {
			if (!isSet[i]) {
				throw new SQLException("Parameter " + (i + 1) + " has not been set");
			}
			sb.append(literal(values[i])).append(parts.get(i + 1));
		}
		return sb.toString();
	}

	/** Same as {@link #bind()} but with the values hidden, for debug logs. */
	String template() {
		StringBuilder sb = new StringBuilder(parts.get(0));
		for (int i = 0; i < values.length; i++) {
			sb.append('?').append(parts.get(i + 1));
		}
		return sb.toString();
	}

	static String literal(Object v) throws SQLException {
		if (v == null) {
			return "NULL";
		}
		if (v instanceof Boolean) {
			return ((Boolean) v) ? "TRUE" : "FALSE";
		}
		if (v instanceof BigDecimal) {
			return ((BigDecimal) v).toPlainString();
		}
		if (v instanceof BigInteger || v instanceof Byte || v instanceof Short || v instanceof Integer
				|| v instanceof Long) {
			return v.toString();
		}
		if (v instanceof Double || v instanceof Float) {
			double d = ((Number) v).doubleValue();
			if (Double.isNaN(d)) {
				return "'NaN'::DOUBLE";
			}
			if (Double.isInfinite(d)) {
				return d > 0 ? "'Infinity'::DOUBLE" : "'-Infinity'::DOUBLE";
			}
			return v.toString();
		}
		if (v instanceof Number) {
			return v.toString();
		}
		if (v instanceof byte[]) {
			StringBuilder hex = new StringBuilder("from_hex('");
			for (byte b : (byte[]) v) {
				hex.append(String.format("%02x", b));
			}
			return hex.append("')").toString();
		}
		// java.sql types are checked by name-free instanceof through their java.time / toString forms
		if (v instanceof java.sql.Timestamp) {
			return "TIMESTAMP " + quote(((java.sql.Timestamp) v).toLocalDateTime().toString().replace('T', ' '));
		}
		if (v instanceof java.sql.Date) {
			return "DATE " + quote(((java.sql.Date) v).toLocalDate().toString());
		}
		if (v instanceof java.sql.Time) {
			return "TIME " + quote(((java.sql.Time) v).toLocalTime().toString());
		}
		if (v instanceof LocalDate) {
			return "DATE " + quote(v.toString());
		}
		if (v instanceof LocalTime) {
			return "TIME " + quote(v.toString());
		}
		if (v instanceof LocalDateTime) {
			return "TIMESTAMP " + quote(v.toString().replace('T', ' '));
		}
		if (v instanceof OffsetDateTime) {
			return "TIMESTAMPTZ " + quote(v.toString().replace('T', ' '));
		}
		if (v instanceof java.util.Date) {
			return "TIMESTAMP " + quote(new java.sql.Timestamp(((java.util.Date) v).getTime()).toLocalDateTime()
					.toString().replace('T', ' '));
		}
		return quote(v.toString()); // String, Character, URL, UUID, enums...
	}

	private static String quote(String s) throws SQLException {
		if (s.indexOf('\0') >= 0) {
			throw new SQLException("A string parameter cannot contain the NUL character");
		}
		return "'" + s.replace("'", "''") + "'";
	}
}
