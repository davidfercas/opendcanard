package io.opencanard.rest;

import java.sql.Connection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.javalin.Javalin;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import io.opencanard.user.UserService;

/**
 * The REST API server (Javalin): wires the OpenAPI/Swagger plugins, the authentication middleware
 * and the routes, and owns the server lifecycle. OpenCanardServer only creates, starts and stops it.
 * <p>
 * Documentation is served at {@code /swagger} (UI) and {@code /openapi} (JSON), the plugin defaults.
 */
public class RestApiServer implements AutoCloseable {

	private static final Logger logger = LogManager.getLogger(RestApiServer.class);

	private final Javalin app;
	private final int port;

	public RestApiServer(Connection duckdb, Connection metadatadb, UserService userService, int port) {
		this.port = port;

		OpenCanardApiController controller = new OpenCanardApiController(duckdb, metadatadb, userService);
		RestRoutes routes = new RestRoutes(controller, new AuthMiddleware(controller));

		this.app = Javalin.create(config -> {
			config.registerPlugin(new OpenApiPlugin(openapi -> {
				openapi.withDefinitionConfiguration((version, builder) -> {
					builder.info(info -> {
						info.title("OpenCanard API");
						info.description("REST API to interact with OpenCanard server");
					});
				});
				openapi.withDefinitionProcessor(content -> {
					try {
						com.fasterxml.jackson.databind.node.ObjectNode components = content.has("components")
								? (com.fasterxml.jackson.databind.node.ObjectNode) content.get("components")
								: content.objectNode();

						com.fasterxml.jackson.databind.node.ObjectNode basicScheme = content.objectNode();
						basicScheme.put("type", "http");
						basicScheme.put("scheme", "basic");

						com.fasterxml.jackson.databind.node.ObjectNode securitySchemes = content.objectNode();
						securitySchemes.set("basicAuth", basicScheme);

						components.set("securitySchemes", securitySchemes);
						content.set("components", components);

					} catch (Exception e) {
						logger.error("Failed to inject security scheme", e);
					} finally {
						return content.toString();
					}
				});
			}));

			config.registerPlugin(new SwaggerPlugin());

			config.jetty.port = this.port;
			routes.register(config.routes);
		});
	}

	public void start() {
		app.start(port);
		logger.info("REST API started on port {} (Swagger UI at /swagger, OpenAPI document at /openapi)", port);
		System.out.println("REST API started at http://localhost:" + port);
	}

	public void stop() {
		app.stop();
		logger.info("REST API stopped");
	}

	@Override
	public void close() {
		stop();
	}
}
