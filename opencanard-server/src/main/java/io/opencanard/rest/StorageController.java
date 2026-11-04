package io.opencanard.rest;

import java.sql.SQLException;
import java.util.ArrayList;
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
import io.opencanard.storage.StorageConfig;
import io.opencanard.storage.StorageRequest;
import io.opencanard.storage.StorageService;
import io.opencanard.user.UserConnections;

/**
 * The S3 storages of the logged-in user ({@code /api/console/storages}). The secret access key can be sent but
 * is never returned: responses only say whether one is stored. Errors are {@code {"error": "..."}} with 400
 * (invalid data), 404 (no such storage) or 409 (name already used).
 */
public class StorageController {

	private static final Logger logger = LogManager.getLogger(StorageController.class);

	private final StorageService storages;
	private final UserConnections connections;

	public StorageController(StorageService storages, UserConnections connections) {
		this.storages = storages;
		this.connections = connections;
	}

	private interface Call {
		void run(String login) throws SQLException;
	}

	@OpenApi(path = "/api/console/storages", methods = HttpMethod.GET, summary = "List my S3 storages",
			description = "The storages of the logged-in user. Secrets are never returned.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Storage" },
			responses = { @OpenApiResponse(status = "200", content = @OpenApiContent(from = List.class)),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials") })
	public void list(Context ctx) {
		handle(ctx, login -> {
			List<Map<String, Object>> out = new ArrayList<>();
			for (StorageConfig s : storages.list(login)) {
				out.add(describe(s, storages.isReadable(s)));
			}
			ctx.json(out);
		});
	}

	@OpenApi(path = "/api/console/storages", methods = HttpMethod.POST, summary = "Add an S3 storage",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Storage" },
			requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = StorageRequest.class), required = true),
			responses = { @OpenApiResponse(status = "201", content = @OpenApiContent(from = Map.class)),
					@OpenApiResponse(status = "400", description = "A field is invalid; the message says which"),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@OpenApiResponse(status = "409", description = "The user already has a storage with that name") })
	public void create(Context ctx) {
		StorageRequest body = body(ctx);
		if (body == null) {
			return;
		}
		handle(ctx, login -> {
			StorageConfig created = storages.create(login, body);
			Map<String, Object> out = describe(created, storages.isReadable(created));
			ctx.status(201);
			ctx.json(withRegistration(login, out));
		});
	}

	@OpenApi(path = "/api/console/storages/{name}", methods = HttpMethod.PUT, summary = "Change an S3 storage",
			description = "Replaces every field but the name. An empty secretAccessKey keeps the stored secret.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Storage" },
			pathParams = { @OpenApiParam(name = "name", required = true) },
			requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = StorageRequest.class), required = true),
			responses = { @OpenApiResponse(status = "200", content = @OpenApiContent(from = Map.class)),
					@OpenApiResponse(status = "400", description = "A field is invalid"),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@OpenApiResponse(status = "404", description = "The user has no storage with that name") })
	public void update(Context ctx) {
		StorageRequest body = body(ctx);
		if (body == null) {
			return;
		}
		handle(ctx, login -> {
			StorageConfig updated = storages.update(login, ctx.pathParam("name"), body);
			ctx.json(withRegistration(login, describe(updated, storages.isReadable(updated))));
		});
	}

	@OpenApi(path = "/api/console/storages/{name}", methods = HttpMethod.DELETE, summary = "Remove an S3 storage",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Storage" },
			pathParams = { @OpenApiParam(name = "name", required = true) },
			responses = { @OpenApiResponse(status = "204", description = "Removed"),
					@OpenApiResponse(status = "200", description = "Removed, but DuckDB could not update its secrets: {\"warning\": \"...\"}"),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@OpenApiResponse(status = "404", description = "The user has no storage with that name") })
	public void delete(Context ctx) {
		handle(ctx, login -> {
			storages.delete(login, ctx.pathParam("name"));
			String warning = register(login);
			if (warning == null) {
				ctx.status(204);
			} else {
				ctx.status(200);
				ctx.json(Map.of("warning", warning));
			}
		});
	}

	/** Makes the change effective in DuckDB right away; a failure is a warning, the change itself is saved. */
	private Map<String, Object> withRegistration(String login, Map<String, Object> storage) {
		String warning = register(login);
		if (warning != null) {
			storage.put("warning", warning);
		}
		return storage;
	}

	private String register(String login) {
		try {
			connections.registerStorages(login);
			return null;
		} catch (Exception e) {
			logger.warn("Storage of '{}' saved, but not registered in DuckDB: {}", login, e.getMessage());
			return "Saved, but DuckDB could not register it for queries: " + e.getMessage();
		}
	}

	private void handle(Context ctx, Call call) {
		String login = ctx.attribute("username");
		try {
			call.run(login);
		} catch (IllegalArgumentException e) {
			error(ctx, 400, e.getMessage());
		} catch (StorageService.NotFoundException e) {
			error(ctx, 404, e.getMessage());
		} catch (StorageService.AlreadyExistsException e) {
			error(ctx, 409, e.getMessage());
		} catch (Exception e) {
			logger.error("Storage request of '{}' failed", login, e);
			error(ctx, 500, "Unexpected error: " + e.getMessage());
		}
	}

	private static StorageRequest body(Context ctx) {
		try {
			StorageRequest body = ctx.bodyAsClass(StorageRequest.class);
			if (body != null) {
				return body;
			}
		} catch (Exception e) {
			// answered below
		}
		error(ctx, 400, "The request body must be JSON with the storage fields");
		return null;
	}

	/** What the API shows about a storage: everything but the secret. */
	static Map<String, Object> describe(StorageConfig s, boolean secretReadable) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", s.getName());
		m.put("bucket", s.getBucket());
		m.put("region", s.getRegion());
		m.put("endpoint", s.getEndpoint());
		m.put("urlStyle", s.getUrlStyle());
		m.put("useSsl", s.isUseSsl());
		m.put("pathPrefix", s.getPathPrefix());
		m.put("accessKeyId", s.getAccessKeyId());
		m.put("hasSecret", true);
		m.put("secretReadable", secretReadable);
		m.put("url", s.url());
		m.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toLocalDateTime().withNano(0).toString().replace('T', ' '));
		m.put("updatedAt", s.getUpdatedAt() == null ? null : s.getUpdatedAt().toLocalDateTime().withNano(0).toString().replace('T', ' '));
		return m;
	}

	private static void error(Context ctx, int status, String message) {
		ctx.status(status);
		ctx.json(Map.of("error", message == null ? "Unknown error" : message));
	}
}
