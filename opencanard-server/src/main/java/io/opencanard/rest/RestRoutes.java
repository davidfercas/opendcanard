package io.opencanard.rest;

import io.javalin.config.RoutesConfig;

/**
 * The REST API route table. To add an endpoint, add a handler method to
 * {@link OpenCanardApiController} (with its {@code @OpenApi} annotation) and register it here.
 */
public class RestRoutes {

	private final OpenCanardApiController controller;
	private final AuthMiddleware authMiddleware;
	private final ConsoleController console;
	private final StorageController storages;
	private final AdminController admin;

	public RestRoutes(OpenCanardApiController controller, ConsoleController console, StorageController storages,
			AdminController admin, AuthMiddleware authMiddleware) {
		this.controller = controller;
		this.admin = admin;
		this.console = console;
		this.storages = storages;
		this.authMiddleware = authMiddleware;
	}

	private byte[] index;

	private synchronized byte[] indexPage() throws java.io.IOException {
		if (index == null) {
			try (java.io.InputStream in = RestRoutes.class.getResourceAsStream("/web/index.html")) {
				if (in == null) {
					throw new java.io.FileNotFoundException("web/index.html is not in the server jar");
				}
				index = in.readAllBytes();
			}
		}
		return index;
	}

	public void register(RoutesConfig routes) {

		// PUBLIC LOGIN: exchange credentials for a token
		routes.post("/api/login", controller::handleLogin);

		// MIDDLEWARE: every management route requires Basic credentials or a Bearer token
		routes.before("/management/*", authMiddleware);

		// PUBLIC
		routes.get("/health", controller::handleHealthCheck);

		// WEB CONSOLE: the page itself is static (see RestApiServer); its API needs a login, any role
		routes.get("/", ctx -> ctx.redirect("/opencanard-console/"));
		// Javalin ignores trailing slashes, so "/opencanard-console" and "/opencanard-console/" reach the same handler: redirect only the
		// form without the slash (the page's relative links need it) and serve the page for the other.
		routes.get("/opencanard-console", ctx -> {
			if (!ctx.path().endsWith("/")) {
				ctx.redirect("/opencanard-console/");
				return;
			}
			ctx.contentType("text/html; charset=utf-8");
			ctx.result(indexPage());
		});
		// Every /api/* call needs a login, except /api/login itself (Javalin ignores a trailing slash, so drop it too)
		routes.before("/api/*", ctx -> {
			String path = ctx.path().endsWith("/") ? ctx.path().substring(0, ctx.path().length() - 1) : ctx.path();
			if (!path.equals("/api/login")) {
				authMiddleware.handle(ctx);
			}
		});
		routes.get("/api/session", console::session);
		routes.get("/api/catalog", console::catalog);
		routes.get("/api/columns", console::columns);
		routes.post("/api/query", console::query);
		routes.get("/api/queries", console::runningQueries);
		routes.post("/api/queries/{id}/cancel", console::cancelQuery);

		// S3 STORAGES of the logged-in user
		routes.get("/api/storages", storages::list);
		routes.post("/api/storages", storages::create);
		routes.post("/api/storages/test", storages::test);
		routes.get("/api/storages/{name}/objects", storages::objects);
		routes.put("/api/storages/{name}", storages::update);
		routes.delete("/api/storages/{name}", storages::delete);

		// MANAGEMENT (admin role)
		routes.get("/management/databases", controller::listDatabases);
		routes.get("/management/schemas", controller::listSchemas);
		routes.get("/management/users", admin::listUsers);
		routes.post("/management/users", admin::createUser);
		routes.put("/management/users/{username}/roles", admin::setUserRoles);
		routes.delete("/management/users/{username}", admin::deleteUser);
		routes.get("/management/roles", admin::listRoles);
		routes.post("/management/roles", admin::createRole);
		routes.put("/management/roles/{name}", admin::updateRole);
		routes.delete("/management/roles/{name}", admin::deleteRole);
		routes.get("/management/privileges", admin::listPrivileges);
		routes.put("/management/users/{username}/privileges/{privilege}", admin::grantUserPrivilege);
		routes.delete("/management/users/{username}/privileges/{privilege}", admin::revokeUserPrivilege);
		routes.put("/management/roles/{name}/privileges/{privilege}", admin::grantRolePrivilege);
		routes.delete("/management/roles/{name}/privileges/{privilege}", admin::revokeRolePrivilege);
		routes.post("/management/shutdown", controller::shutdown);
	}
}
