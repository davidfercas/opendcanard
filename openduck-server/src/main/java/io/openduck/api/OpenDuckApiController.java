package io.openduck.api;

import java.util.Map;

public class OpenDuckApiController {

	
	@io.javalin.openapi.OpenApi(
			path = "/health", 
			methods = io.javalin.openapi.HttpMethod.GET, 
			summary = "Get server status", 
			description = "Checks the operational status of the server and the underlying DuckDB engine.", 
			responses = {
					@io.javalin.openapi.OpenApiResponse(
							status = "200", 
							description = "Server is healthy and functioning properly", 
							content = @io.javalin.openapi.OpenApiContent(from = Map.class)) }
	)	
	public void handleHealthCheck(io.javalin.http.Context ctx) {
		ctx.json(Map.of("status", "UP", "engine", "DuckDB"));
	}
	
}
