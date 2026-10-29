package io.opencanard.driver.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * Conversions between the values a result set column holds (already normalized to JDK types: String,
 * Number, Boolean, byte[], java.sql.Date / Time / Timestamp, java.time types) and what the JDBC getters
 * return. JDK only. A null value converts to the JDBC "null" of each getter (the caller tracks wasNull).
 */
final class ValueConverter {

	private ValueConverter() {
	}

	private static SQLException cannot(Object v, String target) {
		return new SQLException("Cannot convert a value of type " + v.getClass().getName() + " to " + target);
	}

	// ---- numbers ----

	static BigDecimal toBigDecimal(Object v) throws SQLException {
		if (v == null) {
			return null;
		}
		if (v instanceof BigDecimal) {
			return (BigDecimal) v;
		}
		if (v instanceof BigInteger) {
			return new BigDecimal((BigInteger) v);
		}
		if (v instanceof Double || v instanceof Float) {
			return BigDecimal.valueOf(((Number) v).doubleValue());
		}
		if (v instanceof Number) {
			return BigDecimal.valueOf(((Number) v).longValue());
		}
		if (v instanceof Boolean) {
			return ((Boolean) v) ? BigDecimal.ONE : BigDecimal.ZERO;
		}
		if (v instanceof CharSequence) {
			try {
				return new BigDecimal(v.toString().trim());
			} catch (NumberFormatException e) {
				throw new SQLException("Cannot convert '" + v + "' to a number", e);
			}
		}
		throw cannot(v, "BigDecimal");
	}

	static long toLong(Object v) throws SQLException {
		if (v == null) {
			return 0L;
		}
		if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
			return ((Number) v).longValue();
		}
		if (v instanceof Boolean) {
			return ((Boolean) v) ? 1L : 0L;
		}
		if (v instanceof Double || v instanceof Float) {
			return (long) ((Number) v).doubleValue();
		}
		BigDecimal d = toBigDecimal(v);
		try {
			return d.longValueExact();
		} catch (ArithmeticException e) {
			return d.longValue(); // fractional part dropped, like the common drivers
		}
	}

	static int toInt(Object v) throws SQLException {
		long l = toLong(v);
		if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) {
			throw new SQLException("Value " + l + " is out of range for an int");
		}
		return (int) l;
	}

	static short toShort(Object v) throws SQLException {
		long l = toLong(v);
		if (l > Short.MAX_VALUE || l < Short.MIN_VALUE) {
			throw new SQLException("Value " + l + " is out of range for a short");
		}
		return (short) l;
	}

	static byte toByte(Object v) throws SQLException {
		long l = toLong(v);
		if (l > Byte.MAX_VALUE || l < Byte.MIN_VALUE) {
			throw new SQLException("Value " + l + " is out of range for a byte");
		}
		return (byte) l;
	}

	static double toDouble(Object v) throws SQLException {
		if (v == null) {
			return 0.0;
		}
		if (v instanceof Number && !(v instanceof BigDecimal) && !(v instanceof BigInteger)) {
			return ((Number) v).doubleValue();
		}
		if (v instanceof CharSequence) {
			try {
				return Double.parseDouble(v.toString().trim());
			} catch (NumberFormatException e) {
				throw new SQLException("Cannot convert '" + v + "' to a number", e);
			}
		}
		BigDecimal d = toBigDecimal(v);
		return d.doubleValue();
	}

	static float toFloat(Object v) throws SQLException {
		return (float) toDouble(v);
	}

	static boolean toBoolean(Object v) throws SQLException {
		if (v == null) {
			return false;
		}
		if (v instanceof Boolean) {
			return (Boolean) v;
		}
		if (v instanceof Number) {
			return toBigDecimal(v).signum() != 0;
		}
		String s = v.toString().trim();
		if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("t") || s.equalsIgnoreCase("yes") || s.equals("1")) {
			return true;
		}
		if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("f") || s.equalsIgnoreCase("no") || s.equals("0")) {
			return false;
		}
		throw new SQLException("Cannot convert '" + v + "' to a boolean");
	}

	// ---- text and bytes ----

	static String toString(Object v) {
		if (v == null) {
			return null;
		}
		if (v instanceof BigDecimal) {
			return ((BigDecimal) v).toPlainString();
		}
		if (v instanceof byte[]) {
			StringBuilder sb = new StringBuilder();
			for (byte b : (byte[]) v) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
		return v.toString();
	}

	static byte[] toBytes(Object v) throws SQLException {
		if (v == null) {
			return null;
		}
		if (v instanceof byte[]) {
			return (byte[]) v;
		}
		if (v instanceof CharSequence) {
			return v.toString().getBytes(StandardCharsets.UTF_8);
		}
		throw cannot(v, "byte[]");
	}

	// ---- date and time ----

	static Timestamp toTimestamp(Object v) throws SQLException {
		if (v == null) {
			return null;
		}
		if (v instanceof Timestamp) {
			return (Timestamp) v;
		}
		if (v instanceof LocalDateTime) {
			return Timestamp.valueOf((LocalDateTime) v);
		}
		if (v instanceof OffsetDateTime) {
			return Timestamp.from(((OffsetDateTime) v).toInstant());
		}
		if (v instanceof ZonedDateTime) {
			return Timestamp.from(((ZonedDateTime) v).toInstant());
		}
		if (v instanceof Instant) {
			return Timestamp.from((Instant) v);
		}
		if (v instanceof Date) {
			return new Timestamp(((Date) v).getTime());
		}
		if (v instanceof LocalDate) {
			return Timestamp.valueOf(((LocalDate) v).atStartOfDay());
		}
		if (v instanceof java.util.Date) {
			return new Timestamp(((java.util.Date) v).getTime());
		}
		if (v instanceof CharSequence) {
			String s = v.toString().trim();
			try {
				return Timestamp.valueOf(s.length() <= 10 ? s + " 00:00:00" : s.replace('T', ' '));
			} catch (IllegalArgumentException e) {
				throw new SQLException("Cannot convert '" + v + "' to a timestamp", e);
			}
		}
		throw cannot(v, "Timestamp");
	}

	static Date toDate(Object v) throws SQLException {
		if (v == null) {
			return null;
		}
		if (v instanceof Date) {
			return (Date) v;
		}
		if (v instanceof LocalDate) {
			return Date.valueOf((LocalDate) v);
		}
		if (v instanceof CharSequence) {
			String s = v.toString().trim();
			try {
				return Date.valueOf(s.length() > 10 ? s.substring(0, 10) : s);
			} catch (IllegalArgumentException e) {
				throw new SQLException("Cannot convert '" + v + "' to a date", e);
			}
		}
		Timestamp ts = toTimestamp(v);
		return Date.valueOf(ts.toLocalDateTime().toLocalDate());
	}

	static Time toTime(Object v) throws SQLException {
		if (v == null) {
			return null;
		}
		if (v instanceof Time) {
			return (Time) v;
		}
		if (v instanceof LocalTime) {
			return Time.valueOf((LocalTime) v);
		}
		if (v instanceof CharSequence) {
			try {
				return Time.valueOf(v.toString().trim());
			} catch (IllegalArgumentException e) {
				throw new SQLException("Cannot convert '" + v + "' to a time", e);
			}
		}
		Timestamp ts = toTimestamp(v);
		return Time.valueOf(ts.toLocalDateTime().toLocalTime());
	}

	/** An Arrow time-of-day value in the given unit ("SECOND", "MILLISECOND", "MICROSECOND", "NANOSECOND"). */
	static LocalTime timeOfDay(long value, String unit) {
		switch (unit) {
		case "SECOND":
			return LocalTime.ofNanoOfDay(value * 1_000_000_000L);
		case "MILLISECOND":
			return LocalTime.ofNanoOfDay(value * 1_000_000L);
		case "MICROSECOND":
			return LocalTime.ofNanoOfDay(value * 1_000L);
		default:
			return LocalTime.ofNanoOfDay(value);
		}
	}

	/** An Arrow epoch timestamp in the given unit as an Instant. */
	static Instant instant(long value, String unit) {
		switch (unit) {
		case "SECOND":
			return Instant.ofEpochSecond(value);
		case "MILLISECOND":
			return Instant.ofEpochMilli(value);
		case "MICROSECOND":
			return Instant.ofEpochSecond(Math.floorDiv(value, 1_000_000L), Math.floorMod(value, 1_000_000L) * 1_000L);
		default:
			return Instant.ofEpochSecond(Math.floorDiv(value, 1_000_000_000L), Math.floorMod(value, 1_000_000_000L));
		}
	}
}
