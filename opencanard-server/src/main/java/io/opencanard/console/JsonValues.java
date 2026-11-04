package io.opencanard.console;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Struct;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the values of a JDBC result into values a JSON serializer and a JavaScript client handle without
 * losing information: numbers stay numbers when JavaScript can hold them exactly, everything else becomes text.
 * JDK only.
 */
final class JsonValues {

	/** Largest integer that a JavaScript number holds exactly. */
	private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
	/** Longest text sent to the browser for one cell. */
	static final int MAX_TEXT_CHARS = 100_000;
	private static final int MAX_BINARY_BYTES = 64;
	private static final int MAX_DEPTH = 6;

	private JsonValues() {
	}

	static Object convert(Object v) throws SQLException {
		return convert(v, 0);
	}

	private static Object convert(Object v, int depth) throws SQLException {
		if (v == null) {
			return null;
		}
		if (v instanceof Boolean || v instanceof Integer || v instanceof Short || v instanceof Byte) {
			return v;
		}
		if (v instanceof Long) {
			long l = (Long) v;
			return l >= -MAX_SAFE_INTEGER && l <= MAX_SAFE_INTEGER ? (Object) l : String.valueOf(l);
		}
		if (v instanceof Double || v instanceof Float) {
			double d = ((Number) v).doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) {
				return String.valueOf(d);
			}
			return v instanceof Float ? Double.valueOf(v.toString()) : (Object) d;
		}
		if (v instanceof BigDecimal) {
			return ((BigDecimal) v).toPlainString();
		}
		if (v instanceof BigInteger) {
			return v.toString();
		}
		if (v instanceof CharSequence) {
			return limit(v.toString());
		}
		if (v instanceof byte[]) {
			return binary((byte[]) v);
		}
		if (v instanceof Timestamp) {
			return ((Timestamp) v).toLocalDateTime().toString().replace('T', ' ');
		}
		if (v instanceof java.sql.Date) {
			return ((java.sql.Date) v).toLocalDate().toString();
		}
		if (v instanceof java.sql.Time) {
			return ((java.sql.Time) v).toLocalTime().toString();
		}
		if (v instanceof java.time.temporal.TemporalAccessor) { // LocalDate, LocalTime, OffsetDateTime, Instant...
			return v.toString().replace('T', ' ');
		}
		if (depth >= MAX_DEPTH) {
			return limit(v.toString());
		}
		if (v instanceof Array) {
			Object content = ((Array) v).getArray();
			List<Object> list = new ArrayList<>();
			if (content instanceof Object[]) {
				for (Object element : (Object[]) content) {
					list.add(convert(element, depth + 1));
				}
			} else if (content != null) {
				return limit(String.valueOf(content));
			}
			return list;
		}
		if (v instanceof Struct) {
			List<Object> list = new ArrayList<>();
			for (Object attribute : ((Struct) v).getAttributes()) {
				list.add(convert(attribute, depth + 1));
			}
			return list;
		}
		if (v instanceof Map) {
			Map<String, Object> map = new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
				map.put(String.valueOf(e.getKey()), convert(e.getValue(), depth + 1));
			}
			return map;
		}
		if (v instanceof Iterable) {
			List<Object> list = new ArrayList<>();
			for (Object element : (Iterable<?>) v) {
				list.add(convert(element, depth + 1));
			}
			return list;
		}
		return limit(v.toString()); // UUID, enums, intervals, anything else
	}

	private static String binary(byte[] bytes) {
		StringBuilder sb = new StringBuilder("0x");
		int shown = Math.min(bytes.length, MAX_BINARY_BYTES);
		for (int i = 0; i < shown; i++) {
			sb.append(String.format("%02x", bytes[i]));
		}
		if (bytes.length > shown) {
			sb.append("... (").append(bytes.length).append(" bytes)");
		}
		return sb.toString();
	}

	private static String limit(String text) {
		return text.length() <= MAX_TEXT_CHARS ? text
				: text.substring(0, MAX_TEXT_CHARS) + "... (" + text.length() + " characters)";
	}
}
