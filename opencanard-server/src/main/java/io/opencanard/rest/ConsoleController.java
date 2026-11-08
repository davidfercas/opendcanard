package io.opencanard.rest;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;
import io.opencanard.console.CatalogReader;
import io.opencanard.console.QueryRunner;
import io.opencanard.query.RunningQueries;
import io.opencanard.user.UserConnections;

/**
 * Endpoints behind the web console ({@code /opencanard-console/}). Every call runs on the database of the logged-in user,
 * chosen by {@link UserConnections} with the same rule as Arrow Flight: admins work on the metadata database,
 * everyone else on their own registered file. A SQL error is answered with HTTP 400 and {@code {"error": "..."}}.
 */
public class ConsoleController {

	private static final Logger logger = LogManager.getLogger(ConsoleController.class);

	/** Queries kept in the console history when {@code console.history.size} is not set. */
	public static final int DEFAULT_HISTORY_SIZE = 10;

	/** Upper bound of {@code console.history.size}, so a typo cannot fill the browser storage. */
	public static final int MAX_HISTORY_SIZE = 100;

	private final UserConnections userConnections;
	private final RunningQueries runningQueries;
	private final int historySize;

	public ConsoleController(UserConnections userConnections, RunningQueries runningQueries) {
		this(userConnections, runningQueries, DEFAULT_HISTORY_SIZE);
	}

	/**
	 * @param historySize how many past queries the console keeps for each user ({@code console.history.size});
	 *                    0 turns the history off, values above {@link #MAX_HISTORY_SIZE} are capped
	 */
	public ConsoleController(UserConnections userConnections, RunningQueries runningQueries, int historySize) {
		this.userConnections = userConnections;
		this.runningQueries = runningQueries;
		this.historySize = Math.max(0, Math.min(MAX_HISTORY_SIZE, historySize));
	}

	@OpenApi(path = "/api/session", methods = HttpMethod.GET, summary = "Who is connected to the console",
			description = "Returns the login, whether it is an admin, the database the console works on, whether it is "
					+ "opened read-only (privilege READ_ONLY) and how many past queries the console keeps (historySize, "
					+ "from console.history.size).",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Console" },
			responses = { @OpenApiResponse(status = "200", content = @OpenApiContent(from = Map.class)),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials") })
	public void session(Context ctx) {
		String login = login(ctx);
		boolean admin = userConnections.isAdmin(login);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("username", login);
		out.put("admin", admin);
		out.put("database", admin ? "metadata" : "personal");
		boolean readOnly = false;
		if (!admin) {
			try {
				readOnly = userConnections.isReadOnly(login);
			} catch (java.sql.SQLException e) {
				logger.warn("Cannot read the privileges of '{}': {}", login, e.getMessage());
			}
		}
		out.put("readOnly", readOnly);
		out.put("historySize", historySize);
		ctx.json(out);
	}

	@OpenApi(path = "/api/catalog", methods = HttpMethod.GET, summary = "Databases, schemas, tables and views",
			description = "Structure of the database of the logged-in user, for the tree of the console.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Console" },
			responses = { @OpenApiResponse(status = "200", content = @OpenApiContent(from = Map.class)),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@OpenApiResponse(status = "403", description = "The user has no database") })
	public void catalog(Context ctx) {
		withConnection(ctx, conn -> ctx.json(CatalogReader.catalog(conn)));
	}

	@OpenApi(path = "/api/columns", methods = HttpMethod.GET, summary = "Columns of a table or view",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Console" },
			queryParams = { @OpenApiParam(name = "catalog", required = true),
					@OpenApiParam(name = "schema", required = true), @OpenApiParam(name = "table", required = true) },
			responses = { @OpenApiResponse(status = "200", content = @OpenApiContent(from = List.class)),
					@OpenApiResponse(status = "400", description = "A parameter is missing"),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials") })
	public void columns(Context ctx) {
		String catalog = ctx.queryParam("catalog");
		String schema = ctx.queryParam("schema");
		String table = ctx.queryParam("table");
		if (catalog == null || schema == null || table == null) {
			error(ctx, 400, "catalog, schema and table are required");
			return;
		}
		withConnection(ctx, conn -> ctx.json(CatalogReader.columns(conn, catalog, schema, table)));
	}

	@OpenApi(path = "/api/query", methods = HttpMethod.POST, summary = "Run SQL",
			description = "Runs one or more statements separated by ';' and returns the result of the last one.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Console" },
			requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = ConsoleQueryRequest.class), required = true),
			responses = { @OpenApiResponse(status = "200", content = @OpenApiContent(from = Map.class)),
					@OpenApiResponse(status = "400", description = "The SQL is empty or fails; the message says why"),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@OpenApiResponse(status = "403", description = "The user has no database") })
	public void query(Context ctx) {
		ConsoleQueryRequest request;
		try {
			request = ctx.bodyAsClass(ConsoleQueryRequest.class);
		} catch (Exception e) {
			error(ctx, 400, "The request body must be JSON like {\"sql\": \"SELECT 1\"}");
			return;
		}
		if (request == null || request.sql == null || request.sql.isBlank()) {
			error(ctx, 400, "There is no SQL to run");
			return;
		}
		int maxRows = QueryRunner.clampMaxRows(request.maxRows);
		String login = login(ctx);
		withConnection(ctx, conn -> {
			// finish() runs before withConnection closes the connection: a cancel never reaches a closed one
			RunningQueries.Query running = runningQueries.start(login, RunningQueries.Source.CONSOLE, request.sql,
					request.queryId);
			try {
				ctx.json(QueryRunner.run(conn, request.sql, maxRows, running));
			} finally {
				runningQueries.finish(running);
			}
		});
	}

	@OpenApi(path = "/api/queries", methods = HttpMethod.GET, summary = "Queries running now",
			description = "Queries of the web console and of Arrow Flight clients that have not finished yet, oldest "
					+ "first. An admin sees the queries of every user; any other user only their own.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Console" },
			responses = { @OpenApiResponse(status = "200", content = @OpenApiContent(from = Map.class)),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials") })
	public void runningQueries(Context ctx) {
		String login = login(ctx);
		boolean admin = userConnections.isAdmin(login);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("allUsers", admin);
		out.put("queries", runningQueries.list(login, admin));
		ctx.json(out);
	}

	@OpenApi(path = "/api/queries/{id}/cancel", methods = HttpMethod.POST, summary = "Kill a running query",
			description = "Interrupts the query. It ends shortly afterwards with the error \"The query was cancelled\"."
					+ " Users can cancel their own queries; admins can cancel any query.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Console" },
			pathParams = { @OpenApiParam(name = "id", required = true) },
			responses = { @OpenApiResponse(status = "202", description = "The query is being cancelled"),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@OpenApiResponse(status = "404", description = "No running query with that id that the user may cancel; it may have finished") })
	public void cancelQuery(Context ctx) {
		String login = login(ctx);
		boolean admin = userConnections.isAdmin(login);
		if (runningQueries.cancel(ctx.pathParam("id"), login, admin) == RunningQueries.CancelResult.CANCELLED) {
			ctx.status(202);
			ctx.json(Map.of("status", "cancelling"));
		} else {
			error(ctx, 404, "That query is not running any more");
		}
	}

	private interface Action {
		void run(Connection conn) throws SQLException;
	}

	private void withConnection(Context ctx, Action action) {
		String login = login(ctx);
		try (Connection conn = userConnections.open(login)) {
			action.run(conn);
		} catch (UserConnections.NoDatabaseException e) {
			error(ctx, 403, e.getMessage());
		} catch (SQLException e) {
			logger.info("Console request of '{}' failed: {}", login, e.getMessage());
			if ("57014".equals(e.getSQLState())) {
				// cancelled with the Kill button: not a SQL error, the browser shows it differently
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("error", e.getMessage());
				body.put("cancelled", true);
				ctx.status(400);
				ctx.json(body);
				return;
			}
			error(ctx, 400, e.getMessage());
		} catch (IllegalArgumentException e) {
			logger.info("Console request of '{}' failed: {}", login, e.getMessage());
			error(ctx, 400, e.getMessage());
		} catch (Exception e) {
			logger.error("Console request of '{}' crashed", login, e);
			error(ctx, 500, "Unexpected error: " + e);
		}
	}

	private static String login(Context ctx) {
		return ctx.attribute("username");
	}

	private static void error(Context ctx, int status, String message) {
		ctx.status(status);
		ctx.json(Map.of("error", message == null ? "Unknown error" : message));
	}
}
