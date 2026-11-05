package io.opencanard.rest;

/** Body of {@code POST /api/console/query}. */
public class ConsoleQueryRequest {

	/** One or more SQL statements separated by {@code ;}. */
	public String sql;

	/** Most rows to return for the last statement; optional, default 1000, at most 100000. */
	public Integer maxRows;

	/**
	 * Optional id chosen by the browser (letters, digits and dashes, 8 to 64 characters), so it can cancel the
	 * query with {@code POST /api/console/queries/{id}/cancel} before the answer arrives.
	 */
	public String queryId;

	public ConsoleQueryRequest() {
	}
}
