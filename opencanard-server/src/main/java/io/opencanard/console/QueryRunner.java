package io.opencanard.console;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.opencanard.query.RunningQueries;

/**
 * Runs the SQL typed in the web console on one connection and describes what happened in a structure the
 * browser shows directly:
 * <pre>
 * { "columns":    [ { "name": "id", "type": "INTEGER" }, ... ],   // empty when the statement returns no rows
 *   "rows":       [ [ 1, "Ana" ], ... ],
 *   "rowCount":   2,            // rows in "rows"
 *   "truncated":  false,        // true when there were more rows than maxRows
 *   "updateCount": -1,          // rows changed by INSERT / UPDATE / DELETE, -1 for statements that return rows
 *   "statements": 1,            // statements run; the result is the one of the last
 *   "message":    "2 rows",
 *   "elapsedMs":  4 }
 * </pre>
 * A script with several statements is run in order; if one fails, the statements before it have already run.
 */
public final class QueryRunner {

	public static final int DEFAULT_MAX_ROWS = 1000;
	public static final int HARD_MAX_ROWS = 100_000;

	private static final Pattern DML = Pattern.compile(
			"^\\s*(?:(?:--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)\\s*)*(?:INSERT|UPDATE|DELETE|MERGE|COPY)\\b",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private QueryRunner() {
	}

	/** Keeps maxRows between 1 and {@link #HARD_MAX_ROWS}; null or invalid means {@link #DEFAULT_MAX_ROWS}. */
	public static int clampMaxRows(Object requested) {
		if (!(requested instanceof Number)) {
			return DEFAULT_MAX_ROWS;
		}
		long n = ((Number) requested).longValue();
		return (int) Math.max(1, Math.min(HARD_MAX_ROWS, n));
	}

	/**
	 * @throws IllegalArgumentException if the script has no statement
	 * @throws SQLException             if a statement fails; the message names the statement when there are several
	 */
	public static Map<String, Object> run(Connection conn, String script, int maxRows) throws SQLException {
		return run(conn, script, maxRows, null);
	}

	/**
	 * Same as {@link #run(Connection, String, int)}, with each statement attached to {@code query} while it runs
	 * so it can be cancelled; a cancelled script stops and fails with "The query was cancelled".
	 */
	public static Map<String, Object> run(Connection conn, String script, int maxRows, RunningQueries.Query query)
			throws SQLException {
		List<String> statements = SqlSplitter.split(script);
		if (statements.isEmpty()) {
			throw new IllegalArgumentException("There is no SQL to run");
		}

		long started = System.nanoTime();
		Map<String, Object> result = null;
		for (int i = 0; i < statements.size(); i++) {
			boolean last = i == statements.size() - 1;
			try {
				Map<String, Object> one = runOne(conn, statements.get(i), last ? maxRows : 1, query);
				if (last) {
					result = one;
				}
			} catch (SQLException e) {
				if (query != null && query.isCancelled()) {
					throw query.cancelledException();
				}
				if (statements.size() > 1) {
					throw new SQLException("Statement " + (i + 1) + " of " + statements.size() + " failed: " + e.getMessage(),
							e.getSQLState(), e);
				}
				throw e;
			}
		}

		result.put("statements", statements.size());
		result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
		return result;
	}

	private static Map<String, Object> runOne(Connection conn, String sql, int maxRows, RunningQueries.Query query)
			throws SQLException {
		Map<String, Object> out = new LinkedHashMap<>();
		try (Statement stmt = conn.createStatement()) {
			if (query != null) {
				query.attach(stmt);
			}
			try {
				runOn(stmt, sql, maxRows, out);
			} finally {
				if (query != null) {
					query.detach(stmt);
				}
			}
		}
		return out;
	}

	private static void runOn(Statement stmt, String sql, int maxRows, Map<String, Object> out) throws SQLException {
		if (stmt.execute(sql)) {
			try (ResultSet rs = stmt.getResultSet()) {
				readRows(rs, maxRows, out);
			}
		} else {
			int count = stmt.getUpdateCount();
			out.put("columns", new ArrayList<>());
			out.put("rows", new ArrayList<>());
			out.put("rowCount", 0);
			out.put("truncated", false);
			out.put("updateCount", Math.max(count, 0));
			out.put("message", DML.matcher(sql).find() ? rowsText(Math.max(count, 0)) + " affected"
					: "Statement executed");
		}
	}

	private static void readRows(ResultSet rs, int maxRows, Map<String, Object> out) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		int columnCount = meta.getColumnCount();

		List<Map<String, Object>> columns = new ArrayList<>();
		for (int c = 1; c <= columnCount; c++) {
			Map<String, Object> column = new LinkedHashMap<>();
			column.put("name", meta.getColumnLabel(c));
			column.put("type", meta.getColumnTypeName(c));
			columns.add(column);
		}

		List<List<Object>> rows = new ArrayList<>();
		boolean truncated = false;
		while (rs.next()) {
			if (rows.size() >= maxRows) {
				truncated = true; // at least one more row exists
				break;
			}
			List<Object> row = new ArrayList<>(columnCount);
			for (int c = 1; c <= columnCount; c++) {
				row.add(JsonValues.convert(rs.getObject(c)));
			}
			rows.add(row);
		}

		out.put("columns", columns);
		out.put("rows", rows);
		out.put("rowCount", rows.size());
		out.put("truncated", truncated);
		out.put("updateCount", -1);
		out.put("message", rowsText(rows.size()) + (truncated ? " (more rows exist, raise the row limit)" : ""));
	}

	private static String rowsText(long n) {
		return n + (n == 1 ? " row" : " rows");
	}
}
