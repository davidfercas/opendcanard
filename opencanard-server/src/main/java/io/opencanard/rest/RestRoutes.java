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

	public RestRoutes(OpenCanardApiController controller, ConsoleController console, AuthMiddleware authMiddleware) {
		this.controller = controller;
		this.console = console;
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
		routes.get("/", ctx -> ctx.redirect("/console/"));
		// Javalin ignores trailing slashes, so "/console" and "/console/" reach the same handler: redirect only the
		// form without the slash (the page's relative links need it) and serve the page for the other.
		routes.get("/console", ctx -> {
			if (!ctx.path().endsWith("/")) {
				ctx.redirect("/console/");
				return;
			}
			ctx.contentType("text/html; charset=utf-8");
			ctx.result(indexPage());
		});
		routes.before("/api/console/*", authMiddleware);
		routes.get("/api/console/session", console::session);
		routes.get("/api/console/catalog", console::catalog);
		routes.get("/api/console/columns", console::columns);
		routes.post("/api/console/query", console::query);

		// MANAGEMENT (admin role)
		routes.get("/management/databases", controller::listDatabases);
		routes.get("/management/schemas", controller::listSchemas);
		routes.get("/management/users", controller::listUsers);
		routes.post("/management/users", controller::createUser);
		routes.post("/management/shutdown", controller::shutdown);
	}
}
