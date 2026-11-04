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
import io.opencanard.user.UserConnections;

/**
 * Endpoints behind the web console ({@code /console/}). Every call runs on the database of the logged-in user,
 * chosen by {@link UserConnections} with the same rule as Arrow Flight: admins work on the metadata database,
 * everyone else on their own registered file. A SQL error is answered with HTTP 400 and {@code {"error": "..."}}.
 */
public class ConsoleController {

	private static final Logger logger = LogManager.getLogger(ConsoleController.class);

	private final UserConnections userConnections;

	public ConsoleController(UserConnections userConnections) {
		this.userConnections = userConnections;
	}

	@OpenApi(path = "/api/console/session", methods = HttpMethod.GET, summary = "Who is connected to the console",
			description = "Returns the login, whether it is an admin and the database the console works on.",
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
		ctx.json(out);
	}

	@OpenApi(path = "/api/console/catalog", methods = HttpMethod.GET, summary = "Databases, schemas, tables and views",
			description = "Structure of the database of the logged-in user, for the tree of the console.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Console" },
			responses = { @OpenApiResponse(status = "200", content = @OpenApiContent(from = Map.class)),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@OpenApiResponse(status = "403", description = "The user has no database") })
	public void catalog(Context ctx) {
		withConnection(ctx, conn -> ctx.json(CatalogReader.catalog(conn)));
	}

	@OpenApi(path = "/api/console/columns", methods = HttpMethod.GET, summary = "Columns of a table or view",
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

	@OpenApi(path = "/api/console/query", methods = HttpMethod.POST, summary = "Run SQL",
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
		withConnection(ctx, conn -> ctx.json(QueryRunner.run(conn, request.sql, maxRows)));
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
		} catch (SQLException | IllegalArgumentException e) {
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
