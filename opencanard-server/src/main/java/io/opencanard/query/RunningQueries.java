package io.opencanard.query;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The queries that are running right now, from the web console and from Arrow Flight (Python, JDBC, DBeaver).
 * <p>
 * A query is registered with {@link #start} before it runs and removed with {@link #finish} when it ends, in a
 * {@code finally} block that runs <b>before</b> its connection is closed. While a JDBC {@link Statement} executes
 * or its rows are read, it is attached to the query, so {@link #cancel} can interrupt it: the DuckDB driver's
 * {@code Statement.cancel()} interrupts the query running on that statement's connection. Every request works
 * on its own duplicated connection, so the interrupt never reaches another query.
 * <p>
 * Visibility: an admin sees and can cancel every query; any other user only their own.
 */
public class RunningQueries {

	private static final Logger logger = LogManager.getLogger(RunningQueries.class);

	/** Ids sent by the browser: letters, digits and dashes, so they are safe in a URL. */
	private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

	/** Longest SQL kept for the list; a query with megabytes of literals should not travel on every refresh. */
	static final int MAX_SQL = 4000;

	public enum Source {
		CONSOLE("Console"), FLIGHT("Arrow Flight");

		private final String label;

		Source(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	public enum CancelResult {
		/** The interrupt was sent; the query ends with an error shortly. */
		CANCELLED,
		/** No running query has that id, or the caller may not see it. */
		NOT_FOUND
	}

	/** One running query. Thread-safe: the thread running it and the thread cancelling it both use it. */
	public static final class Query {
		private final String id;
		private final String login;
		private final Source source;
		private final String sql;
		private final Instant started = Instant.now();
		private Statement current;
		private boolean finished;
		private String cancelledBy;

		private Query(String id, String login, Source source, String sql) {
			this.id = id;
			this.login = login;
			this.source = source;
			this.sql = sql.length() > MAX_SQL ? sql.substring(0, MAX_SQL) + "…" : sql;
		}

		public String getId() {
			return id;
		}

		public String getLogin() {
			return login;
		}

		/**
		 * Call right after creating each Statement of the query, before executing it. Fails if the query was
		 * cancelled already, so a script stops before its next statement.
		 */
		public synchronized void attach(Statement statement) throws SQLException {
			if (cancelledBy != null) {
				throw cancelledException();
			}
			current = statement;
		}

		/** Call when the statement is done (its rows read), before it is closed. */
		public synchronized void detach(Statement statement) {
			if (current == statement) {
				current = null;
			}
		}

		public synchronized boolean isCancelled() {
			return cancelledBy != null;
		}

		/** Who cancelled the query, or null. */
		public synchronized String cancelledBy() {
			return cancelledBy;
		}

		/** The error to report for a query that was cancelled. */
		public synchronized SQLException cancelledException() {
			String by = cancelledBy == null || cancelledBy.equals(login) ? "" : " by " + cancelledBy;
			return new SQLException("The query was cancelled" + by + ".", "57014");
		}

		/**
		 * Translates the error a cancelled query ends with ("INTERRUPT Error: Interrupted!") into a clear message.
		 * Other errors are returned as they are.
		 */
		public SQLException explain(SQLException e) {
			return isCancelled() ? cancelledException() : e;
		}

		private synchronized boolean cancel(String by) {
			if (finished) {
				return false;
			}
			if (cancelledBy == null) {
				cancelledBy = by;
			}
			if (current != null) {
				try {
					current.cancel();
				} catch (SQLException e) {
					logger.warn("[queries] interrupting query {} of '{}' failed: {}", id, login, e.getMessage());
				}
			}
			return true;
		}

		private synchronized void markFinished() {
			finished = true;
			current = null;
		}

		private synchronized Map<String, Object> describe(Instant now) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("id", id);
			out.put("user", login);
			out.put("source", source.label());
			out.put("sql", sql);
			out.put("startedAt", started.toString());
			out.put("elapsedMs", Math.max(0, now.toEpochMilli() - started.toEpochMilli()));
			out.put("state", cancelledBy == null ? "running" : "cancelling");
			return out;
		}
	}

	private final Map<String, Query> running = new ConcurrentHashMap<>();

	/**
	 * Registers a query that is about to run. {@code requestedId} is the id the browser chose so it can cancel
	 * its own query before the answer arrives; it is ignored (a new id is made) when it is missing, malformed or
	 * already in use.
	 */
	public Query start(String login, Source source, String sql, String requestedId) {
		String id = requestedId != null && CLIENT_ID.matcher(requestedId).matches() ? requestedId : null;
		while (true) {
			String candidate = id != null ? id : UUID.randomUUID().toString();
			Query q = new Query(candidate, login, source, sql == null ? "" : sql);
			if (running.putIfAbsent(candidate, q) == null) {
				logger.debug("[queries] {} started {} for '{}'", source.label(), candidate, login);
				return q;
			}
			id = null; // taken: make one up
		}
	}

	/** Removes a query. Call it in a finally block before the query's connection is closed. */
	public void finish(Query q) {
		if (q == null) {
			return;
		}
		q.markFinished();
		running.remove(q.getId(), q);
	}

	/** The queries {@code login} may see, oldest first. */
	public List<Map<String, Object>> list(String login, boolean admin) {
		Instant now = Instant.now();
		List<Query> visible = new ArrayList<>();
		for (Query q : running.values()) {
			if (admin || q.getLogin().equals(login)) {
				visible.add(q);
			}
		}
		visible.sort(Comparator.comparing((Query q) -> q.started).thenComparing(Query::getId));
		List<Map<String, Object>> out = new ArrayList<>(visible.size());
		for (Query q : visible) {
			out.add(q.describe(now));
		}
		return out;
	}

	/** Cancels a query of {@code login}, or of anyone when {@code admin}. */
	public CancelResult cancel(String id, String login, boolean admin) {
		Query q = id == null ? null : running.get(id);
		if (q == null || !(admin || q.getLogin().equals(login))) {
			return CancelResult.NOT_FOUND;
		}
		if (!q.cancel(login)) {
			return CancelResult.NOT_FOUND; // it ended meanwhile
		}
		logger.info("[queries] '{}' cancelled query {} of '{}'", login, id, q.getLogin());
		return CancelResult.CANCELLED;
	}

	public int size() {
		return running.size();
	}
}
