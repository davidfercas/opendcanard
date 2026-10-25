package io.opencanard.rest;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import io.opencanard.user.User;
import io.opencanard.user.UserAlreadyExistsException;
import io.opencanard.user.UserRepository;
import io.opencanard.user.UserService;


public class OpenCanardApiController {

	
	private static final Logger logger = LogManager.getLogger(OpenCanardApiController.class);
	
	private final Connection metadatadb;
	private final Connection duckdb;
	private final UserService userService;
	
	// Shared with AuthMiddleware, which verifies the tokens issued by handleLogin
	static final String JWT_SECRET = "your-super-secret-key-change-this";
	
	public OpenCanardApiController(Connection duckdb, Connection metadatadb, UserService userService) {
		super();
		this.metadatadb = metadatadb;
		this.duckdb = duckdb;
		this.userService = userService;
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
        
        requireAdmin(ctx);
        
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
	
	
	public void listSchemas(io.javalin.http.Context ctx) {
		requireAdmin(ctx);

		List<Map<String, Object>> schemas = new ArrayList<>();

		// Query the DuckDB catalog
		try (Statement stmt = this.duckdb.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT * FROM duckdb_schemas();")) {

			while (rs.next()) {
				schemas.add(Map.of(
						"oid", rs.getLong("oid"),
						"database_name", rs.getString("database_name"),
						"database_oid", rs.getLong("database_oid"),
						"schema_name", rs.getString("schema_name"),
						"comment", rs.getString("comment") != null ? rs.getString("comment") : "",
						"tags", rs.getObject("tags") != null ? rs.getObject("tags") : Collections.emptyMap(),
						"internal", rs.getBoolean("internal"),
						"sql", rs.getString("sql") != null ? rs.getString("sql") : ""));
			}

			ctx.json(schemas);
		} catch (SQLException e) {
			logger.error("Failed to fetch schemas", e);
			ctx.status(500).result("Internal Server Error: " + e.getMessage());
		}
	}

	@io.javalin.openapi.OpenApi(
			path = "/management/users",
			methods = io.javalin.openapi.HttpMethod.GET,
			summary = "List users",
			description = "Lists the users (never their password hashes). Requires the admin role.",
			security = { @io.javalin.openapi.OpenApiSecurity(name = "basicAuth") },
			tags = { "Management" },
			responses = {
					@io.javalin.openapi.OpenApiResponse(status = "200", description = "List of users"),
					@io.javalin.openapi.OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@io.javalin.openapi.OpenApiResponse(status = "403", description = "User does not have the admin role")
			}
	)
	public void listUsers(io.javalin.http.Context ctx) {
		requireAdmin(ctx);

		List<Map<String, Object>> users = new ArrayList<>();

		try (Statement stmt = this.metadatadb.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT * FROM opencanard_users;")) {

			while (rs.next()) {
				users.add(Map.of(
						"username", rs.getString("username"),
						"role", rs.getString("role") != null ? rs.getString("role") : "USER",
						"created_at", rs.getTimestamp("created_at").toString()));
			}

			ctx.json(users);
		} catch (SQLException e) {
			logger.error("Failed to fetch users", e);
			ctx.status(500).result("Internal Server Error: " + e.getMessage());
		}
	}

	@io.javalin.openapi.OpenApi(
			path = "/management/users",
			methods = io.javalin.openapi.HttpMethod.POST,
			summary = "Create a user",
			description = "Creates a user and the user's own DuckDB database file, named after the login. "
					+ "The username may only contain letters, digits, '_' and '-' (max. 64 characters). "
					+ "The role is 'admin' or 'user' (default). Requires the admin role.",
			security = { @io.javalin.openapi.OpenApiSecurity(name = "basicAuth") },
			tags = { "Management" },
			requestBody = @io.javalin.openapi.OpenApiRequestBody(
					required = true,
					content = @io.javalin.openapi.OpenApiContent(from = CreateUserRequest.class)),
			responses = {
					@io.javalin.openapi.OpenApiResponse(
							status = "201",
							description = "User created",
							content = @io.javalin.openapi.OpenApiContent(from = CreateUserResponse.class)),
					@io.javalin.openapi.OpenApiResponse(status = "400", description = "Invalid username, password or role"),
					@io.javalin.openapi.OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@io.javalin.openapi.OpenApiResponse(status = "403", description = "User does not have the admin role"),
					@io.javalin.openapi.OpenApiResponse(status = "409", description = "The user already exists")
			}
	)
	public void createUser(io.javalin.http.Context ctx) {
		requireAdmin(ctx);

		Map<String, Object> body = ctx.bodyAsClass(Map.class);
		String username = body.get("username") instanceof String ? (String) body.get("username") : null;
		String password = body.get("password") instanceof String ? (String) body.get("password") : null;
		String role = body.get("role") instanceof String ? (String) body.get("role") : null;

		try {
			User user = userService.createUser(username, password, role);
			String role0 = user.getRoles().get(0);
			logger.info("User '{}' created by '{}'", user.getUsername(), (String) ctx.attribute("username"));
			ctx.status(201).json(Map.of(
					"username", user.getUsername(),
					"role", role0,
					"database", user.getUsername() + ".duckdb"));
		} catch (IllegalArgumentException e) {
			throw new io.javalin.http.BadRequestResponse(e.getMessage());
		} catch (UserAlreadyExistsException e) {
			throw new io.javalin.http.ConflictResponse(e.getMessage());
		} catch (Exception e) {
			logger.error("Failed to create user '{}'", username, e);
			ctx.status(500).result("Internal Server Error: the user could not be created");
		}
	}

	public void shutdown(io.javalin.http.Context ctx) {
		ctx.result("Shutting down...");
		System.exit(0);
	}

	/** Throws 403 unless the authenticated user (set by AuthMiddleware) has the admin role. */
	private void requireAdmin(io.javalin.http.Context ctx) {
		List<String> roles = ctx.attribute("userRoles");
		if (roles == null || !roles.contains("admin")) {
			throw new io.javalin.http.ForbiddenResponse("You need the ADMIN role to perform this action.");
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
