package io.openduck.api;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import io.openduck.user.User;
import io.openduck.user.UserRepository;


public class OpenDuckApiController {

	
	private static final Logger logger = LogManager.getLogger(OpenDuckApiController.class);
	
	private final Connection metadatadb;
	private final Connection duckdb;
	
	private final String JWT_SECRET = "your-super-secret-key-change-this";
	
	public OpenDuckApiController(Connection duckdb, Connection metadatadb) {
		super();
		this.metadatadb = metadatadb;
		this.duckdb = duckdb;
	}


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
	
	
	@io.javalin.openapi.OpenApi(
			path = "/management/databases", 
			methods = io.javalin.openapi.HttpMethod.GET, 
			summary = "Get list of databases", 
			description = "Checks the list of databases in the DuckDB engine.", 
		    security = {
	            @io.javalin.openapi.OpenApiSecurity(name = "basicAuth")
	        },

	        tags = { "Management" },			
			responses = {
					@io.javalin.openapi.OpenApiResponse(
							status = "200", 
							description = "Server is healthy and functioning properly", 
							content = @io.javalin.openapi.OpenApiContent(from = Map.class)) ,
					@io.javalin.openapi.OpenApiResponse(
							status = "401",
							description = "Missing or invalid JWT token"
			         ),

			         @io.javalin.openapi.OpenApiResponse(
			        		 status = "403",
			        		 description = "User does not have ADMIN role"
			         )
			}
	)		
	
	public void listDatabases(io.javalin.http.Context ctx) {
		
		List<Map<String, Object>> databases = new ArrayList<>();
        
        List<String> roles = ctx.attribute("userRoles");
        
        if (roles == null || !roles.contains("admin")) {
            throw new io.javalin.http.ForbiddenResponse("You need the ADMIN role to perform this action.");
        }
        
        // Query the DuckDB catalog
        try (Statement stmt = this.duckdb.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA show_databases;")) {
            
            while (rs.next()) {
                databases.add(Map.of(
                    "name", rs.getString("database_name")
                ));
            }
            
            ctx.json(databases);
        } catch (SQLException e) {
            logger.error("Failed to fetch databases", e);
            ctx.status(500).result("Internal Server Error: " + e.getMessage());
        }
	}
	
	
	@io.javalin.openapi.OpenApi(
		    path = "/api/login",
		    methods = io.javalin.openapi.HttpMethod.POST,
		    summary = "Login",
		    description = "Exchange credentials for a JWT token",
		    tags = { "Auth" },
		    requestBody = @io.javalin.openapi.OpenApiRequestBody(
		        required = true,
		        content = @io.javalin.openapi.OpenApiContent(from = LoginRequest.class)  // <-- clase propia
		    ),
		    responses = {
		        @io.javalin.openapi.OpenApiResponse(
		            status = "200",
		            description = "JWT token",
		            content = @io.javalin.openapi.OpenApiContent(from = LoginResponse.class)  // <-- clase propia
		        ),
		        @io.javalin.openapi.OpenApiResponse(status = "401", description = "Invalid credentials")
		    }
		)
	public void handleLogin(io.javalin.http.Context ctx){
        Map<String, String> body = ctx.bodyAsClass(Map.class);
        String username = body.get("username");
        String password = body.get("password");
        User user = authenticateUser(username, password);
       
        if (user != null) {
            // Generate the token
            String token = com.auth0.jwt.JWT.create()
                .withClaim("username", user.getUsername())
                .withArrayClaim("roles", user.getRoles().toArray(new String[0]))
                .withExpiresAt(new java.util.Date(System.currentTimeMillis() + 3600000)) // 1 hour
                .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(JWT_SECRET));
            ctx.json(Map.of("token", token));
        } else {
            throw new io.javalin.http.UnauthorizedResponse("Invalid Credentials");
        }
        
	}
	
	public User authenticateUser(String username, String password) {

		// Decode and verify credentials...
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

		UserRepository userRepository = new UserRepository();
		
		try {
			User user = userRepository.getUser(this.metadatadb, username);
			if (user == null || password == null) {
				return null;
			}
			if (encoder.matches(password, user.getPasswordHash())) {
				return user;
			}
			return null;
			
		} catch (Exception e) {
			logger.error(e);
			return null;
		}
				
	}

}
