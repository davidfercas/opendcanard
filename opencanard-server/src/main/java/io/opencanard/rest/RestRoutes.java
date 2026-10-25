package io.opencanard.rest;

import io.javalin.config.RoutesConfig;

/**
 * The REST API route table. To add an endpoint, add a handler method to
 * {@link OpenCanardApiController} (with its {@code @OpenApi} annotation) and register it here.
 */
public class RestRoutes {

	private final OpenCanardApiController controller;
	private final AuthMiddleware authMiddleware;

	public RestRoutes(OpenCanardApiController controller, AuthMiddleware authMiddleware) {
		this.controller = controller;
		this.authMiddleware = authMiddleware;
	}

	public void register(RoutesConfig routes) {

		// PUBLIC LOGIN: exchange credentials for a token
		routes.post("/api/login", controller::handleLogin);

		// MIDDLEWARE: every management route requires Basic credentials or a Bearer token
		routes.before("/management/*", authMiddleware);

		// PUBLIC
		routes.get("/health", controller::handleHealthCheck);

		// MANAGEMENT (admin role)
		routes.get("/management/databases", controller::listDatabases);
		routes.get("/management/schemas", controller::listSchemas);
		routes.get("/management/users", controller::listUsers);
		routes.post("/management/users", controller::createUser);
		routes.post("/management/shutdown", controller::shutdown);
	}
}
