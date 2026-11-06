package io.opencanard.util;

import org.apache.arrow.adapter.jdbc.JdbcToArrowConfig;
import org.apache.arrow.adapter.jdbc.JdbcToArrowConfigBuilder;
import org.apache.arrow.adapter.jdbc.JdbcToArrowUtils;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBResultSet;

import io.opencanard.query.RunningQueries;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public final class DuckDBArrowUtil {

	private static final Logger logger = LogManager.getLogger(DuckDBArrowUtil.class);

	private DuckDBArrowUtil() {
	}

	/**
	 * What running a statement produced: either a schema (the statement returns rows) or an update count
	 * (DDL / DML such as CREATE TABLE, INSERT, UPDATE, DELETE, which return no result set).
	 */
	public static final class Outcome {
		private final org.apache.arrow.vector.types.pojo.Schema schema;
		private final long updateCount;

		private Outcome(org.apache.arrow.vector.types.pojo.Schema schema, long updateCount) {
			this.schema = schema;
			this.updateCount = updateCount;
		}

		public boolean returnsRows() {
			return schema != null;
		}

		/** The schema of the rows, or null if the statement returns none. */
		public org.apache.arrow.vector.types.pojo.Schema getSchema() {
			return schema;
		}

		/** Rows changed by a statement that returns no rows (0 for DDL). */
		public long getUpdateCount() {
			return updateCount;
		}
	}

	/**
	 * Runs the statement ONCE with {@code execute}, which accepts any kind of SQL. {@code executeQuery}
	 * fails for statements without a result set ("executeQuery() can only be used with queries that
	 * return a ResultSet"). A statement that returns no rows has done its work when this returns; one that
	 * returns rows is run again by {@link #streamQuery} to stream them.
	 */
	public static Outcome run(Connection conn, String sql) throws Exception {
		return run(conn, sql, null);
	}

	/** Same as {@link #run(Connection, String)}, cancellable through {@code query} (may be null). */
	public static Outcome run(Connection conn, String sql, RunningQueries.Query query) throws Exception {
		try (Statement stmt = conn.createStatement()) {
			if (query != null) {
				query.attach(stmt);
			}
			try {
				return runOn(stmt, sql);
			} finally {
				if (query != null) {
					query.detach(stmt);
				}
			}
		} catch (java.sql.SQLException e) {
			java.sql.SQLException shown = query == null ? e : query.explain(e);
			if (shown == e) {
				logger.error("Error running {}", sql, e);
			}
			throw shown;
		}
	}

	private static Outcome runOn(Statement stmt, String sql) throws Exception {
		boolean first = stmt.execute(sql);
		boolean update = isUpdate(first, sql);
		logger.info("[flight] execute() returned {}, treated as {}: {}", first, update ? "UPDATE" : "QUERY",
				abbreviate(sql));

		if (!update) {
			try (ResultSet rs = stmt.getResultSet()) {
				JdbcToArrowConfig config = new JdbcToArrowConfigBuilder().setAllocator(new RootAllocator())
						.setTargetBatchSize(1024).build();
				return new Outcome(JdbcToArrowUtils.jdbcToArrowSchema(rs.getMetaData(), config), -1);
			}
		}

		return new Outcome(null, updateCount(stmt, first));
	}

	/**
	 * Whether a statement that {@code execute} answered {@code first} for has no rows to stream. The JDBC driver's
	 * answer is not trusted alone: a plain INSERT / UPDATE / DELETE / CREATE... never has rows to stream, and
	 * streaming it would run executeQuery on it and fail.
	 */
	public static boolean isUpdate(boolean first, String sql) {
		return !first || (WRITE_STATEMENT.matcher(sql).find() && !RETURNING.matcher(sql).find());
	}

	/** Rows changed by the statement {@code stmt} just executed (0 for DDL). Reads the count once. */
	public static long updateCount(Statement stmt, boolean first) throws java.sql.SQLException {
		long count = Math.max(stmt.getUpdateCount(), 0);
		if (first && count == 0) {
			// Some DuckDB versions report the changed rows as a one-row result set named "Count"
			try (ResultSet rs = stmt.getResultSet()) {
				if (rs != null && rs.next()) {
					count = rs.getLong(1);
				}
			} catch (Exception ignored) {
				// no result set to read
			}
		}
		return count;
	}

	/** Statements that change data or settings and never return rows (unless they have RETURNING). */
	private static final java.util.regex.Pattern WRITE_STATEMENT = java.util.regex.Pattern.compile(
			"^\\s*(?:(?:--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)\\s*)*(?:INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|ATTACH|DETACH|"
					+ "CHECKPOINT|VACUUM|ANALYZE|SET|RESET|USE|LOAD|INSTALL|BEGIN|COMMIT|ROLLBACK|COMMENT|GRANT|REVOKE)\\b",
			java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
	private static final java.util.regex.Pattern RETURNING = java.util.regex.Pattern.compile("\\bRETURNING\\b",
			java.util.regex.Pattern.CASE_INSENSITIVE);

	private static String abbreviate(String sql) {
		String oneLine = sql.replaceAll("\\s+", " ").trim();
		return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...";
	}

	// Get Arrow schema
	public static org.apache.arrow.vector.types.pojo.Schema getSchema(Connection conn, String sql) throws Exception {

		try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {

			JdbcToArrowConfig config = new JdbcToArrowConfigBuilder().setAllocator(new RootAllocator())
					.setTargetBatchSize(1024)
					// If the error persists, you may need a custom field mapper here
					.build();

			return JdbcToArrowUtils.jdbcToArrowSchema(rs.getMetaData(), config);
		} catch (Exception e) {
			logger.error(e);
			throw e;
		}
	}

	// Stream query to Flight
	public static void streamQuery(Connection conn, String sql, FlightProducer.ServerStreamListener listener)
			throws Exception {
		streamQuery(conn, sql, listener, null);
	}

	/**
	 * Same as {@link #streamQuery(Connection, String, FlightProducer.ServerStreamListener)}, cancellable through
	 * {@code query} (may be null) while it executes and while its batches are sent.
	 */
	public static void streamQuery(Connection conn, String sql, FlightProducer.ServerStreamListener listener,
			RunningQueries.Query query) throws Exception {

		try (Statement stmt = conn.createStatement()) {
			if (query != null) {
				query.attach(stmt);
			}
			try (DuckDBResultSet rs = executeQuery(stmt, sql, query)) {
				// This is the "magic" method that uses the loaded 'arrow' extension
				BufferAllocator allocator = new RootAllocator();
				try (ArrowReader reader = (ArrowReader) rs.arrowExportStream(allocator, 1024)) {
					VectorSchemaRoot root = reader.getVectorSchemaRoot();
					listener.start(root);

					while (!listener.isCancelled() && reader.loadNextBatch()) {
						// Only send if batch has rows
						if (root.getRowCount() > 0) {
							listener.putNext();
						}
					}

					if (!listener.isCancelled()) {
						listener.completed();
					}
				} catch (Exception e) {
					if (query != null && query.isCancelled()) {
						logger.info("[flight-sql] query {} cancelled by '{}' while streaming", query.getId(),
								query.cancelledBy());
						listener.error(CallStatus.CANCELLED.withDescription(query.cancelledException().getMessage())
								.toRuntimeException());
					} else {
						logger.error("Flight server error", e);
						listener.error(CallStatus.INTERNAL.withCause(e).withDescription(e.getMessage())
								.toRuntimeException());
					}
				}
			} finally {
				if (query != null) {
					query.detach(stmt);
				}
			}
		}
	}

	// Cast the result set to DuckDBResultSet to access native Arrow methods
	private static DuckDBResultSet executeQuery(Statement stmt, String sql, RunningQueries.Query query)
			throws java.sql.SQLException {
		try {
			return (DuckDBResultSet) stmt.executeQuery(sql);
		} catch (java.sql.SQLException e) {
			throw query == null ? e : query.explain(e);
		}
	}
}
