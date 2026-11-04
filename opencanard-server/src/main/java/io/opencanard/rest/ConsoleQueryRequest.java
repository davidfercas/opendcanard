package io.opencanard.rest;

/** Body of {@code POST /api/console/query}. */
public class ConsoleQueryRequest {

	/** One or more SQL statements separated by {@code ;}. */
	public String sql;

	/** Most rows to return for the last statement; optional, default 1000, at most 100000. */
	public Integer maxRows;

	public ConsoleQueryRequest() {
	}
}
