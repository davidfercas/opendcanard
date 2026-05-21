package io.openduck.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.arrow.adapter.jdbc.JdbcToArrowConfig;
import org.apache.arrow.adapter.jdbc.JdbcToArrowConfigBuilder;
import org.apache.arrow.adapter.jdbc.JdbcToArrowUtils;
import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.ActionType;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SchemaResult;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import io.grpc.internal.LogExceptionRunnable;
import io.javalin.Javalin;
import io.javalin.http.ForbiddenResponse;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import io.openduck.api.OpenDuckApiController;
import io.openduck.auth.OpenDuckAuthenticator;
import io.openduck.conf.Config;
import io.openduck.user.User;
import io.openduck.user.UserRepository;
import io.openduck.util.DuckDBArrowUtil;

public class OpenDuckServer implements FlightProducer, AutoCloseable {

	private final Connection duckdb;
	private final Connection metadatadb;
	private final FlightServer server;
	private final BufferAllocator allocator;
	private final Javalin restServer; // New REST server instance
	
	private final OpenDuckApiController openDuckApiController;

	private int restPort = 8080;

	private final String JWT_SECRET = "your-super-secret-key-change-this";
	
	private static final Logger logger = LogManager.getLogger(OpenDuckServer.class);

	
    public static final String CONF_DIR;
    public static final String DATA_DIR;
    public static final String LOG_DIR;
    public static final String METADATA_DIR;

    static {
    	System.out.println("Loading properties");
        CONF_DIR = require("conf.dir");
        DATA_DIR = require("data.dir");
        LOG_DIR = require("log.dir");
        METADATA_DIR = require("metadata.dir");
    }

    private static String require(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
        	System.out.println("Missing system property: " + key);
            throw new IllegalStateException("Missing system property: " + key);
        }
        return value;
    }
	
	public static void main(String[] args) throws Exception {

		// -Djava.io.tmpdir=/path/to/a/bigger/disk/folder
		// Standard practice: Use try-with-resources to prevent memory leaks
		
		System.out.println("CONF DIR = " + CONF_DIR);
		System.out.println("DATA DIR = " + DATA_DIR);
		System.out.println("METADATA DIR = " + METADATA_DIR);
		System.out.println("LOG DIR = " + LOG_DIR);
		
		Path openduckdb = Paths.get(DATA_DIR, Config.get("openduck.db")).toAbsolutePath().normalize();		
		Path metadatadb = Paths.get(METADATA_DIR, Config.get("metadatadb.db")).toAbsolutePath().normalize();
		
		try (BufferAllocator rootAllocator = new RootAllocator(Config.getInt("arrow.memory.mb") * 1024L * 1024L)) {
			// You now have a BufferAllocator instance!
			// By default, it has no limit, but you can set one:
			// new RootAllocator(1024 * 1024 * 1024L); // 1GB Limit
			OpenDuckServer server = new OpenDuckServer(Config.get("openduck.host"), Config.getInt("openduck.port"),
					metadatadb.toString().replace("\\", "/"), openduckdb.toString().replace("\\", "/"), rootAllocator);

			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					server.close();
				} catch (Exception ignored) {
				}
			}));

			server.start();

			System.out.println("Press Ctrl+C to stop.");
			Thread.currentThread().join();

		} catch (Exception e) {
			logger.error(e);
			throw e;
		}
	}

	public OpenDuckServer(String host, int arrowPort, String metadataPath, String duckdbPath, BufferAllocator allocator)
			throws Exception {

		
		
		// Embedded DuckDB
		try {
			this.duckdb = DriverManager.getConnection("jdbc:duckdb:" + duckdbPath);
			this.metadatadb = DriverManager.getConnection("jdbc:duckdb:" + metadataPath);
		} catch (Exception e) {
			logger.error(e);
			throw e;
		}

		try (Statement stmt = this.duckdb.createStatement()) {

			if (!stmt.execute("INSTALL arrow;")) {
				int count = stmt.getUpdateCount();
				if (count == -1) {
					System.out.println("Command \"INSTALL arrow;\" executed successfully (no rows affected).");
					logger.info("Command \"INSTALL arrow;\" executed successfully (no rows affected).");
				} else {
					System.out.println("Rows affected: " + count);
				}
			}

			if (!stmt.execute("LOAD arrow;")) {
				int count = stmt.getUpdateCount();
				if (count == -1) {
					System.out.println("Command \"LOAD arrow;\" executed successfully (no rows affected).");
					logger.info("Command \"LOAD arrow;\" executed successfully (no rows affected).");
				} else {
					System.out.println("Rows affected: " + count);
				}
			}

		} catch (Exception e) {
			logger.error(e);
			throw e;
		}
		// Flight gRPC location
		Location location = Location.forGrpcInsecure(host, arrowPort);

		// Build server
		this.allocator = allocator;
	//	this.server = FlightServer.builder(this.allocator, location, this).middleware(FlightServerMiddleware.Key.of("auth"),new AuthMiddlewareFactory("mysecret123")).build();
		this.server = FlightServer.builder(this.allocator, location, this).headerAuthenticator(new OpenDuckAuthenticator(this.metadatadb)).build();
		//this.server = FlightServer.builder(this.allocator, location, this).headerAuthenticator(CallHeaderAuthenticator.NO_OP).build(); 
		
		// 3. REST API Setup (using Javalin)
		
		this.openDuckApiController = new OpenDuckApiController();
		
        this.restServer = Javalin.create(config -> {
            // Registered exactly as per the doc structure
            config.registerPlugin(new OpenApiPlugin(openapi -> {
                openapi.withDefinitionConfiguration((version, builder) -> {
                    builder.info(info -> {
                        info.title("OpenDuck API");
                        info.description("REST API to interact with OpenDuck server");
                    });
                });
            }));

            config.registerPlugin(new SwaggerPlugin());
            
            // Set port and handle routes encapsulation
            config.jetty.port = this.restPort;
            setupRestRoutes(config.routes);
        });
        

		
        // Set REST API port
        this.restPort = Config.getInt("openduck.rest.port");
		
       // setupRestRoutes();

	}

	
	private void setupRestRoutes(io.javalin.config.RoutesConfig routes) {
		
	    // PUBLIC LOGIN: Exchange credentials for a Token
	    routes.post("/api/login", ctx -> {
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
	    });

	    // MIDDLEWARE: Protect management routes using the Token
	    routes.before("/management/*", ctx -> {
	        String authHeader = ctx.header("Authorization");
	        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
	            throw new io.javalin.http.UnauthorizedResponse("Missing Token");
	        }
	        
	        try {
	            String token = authHeader.substring(7);
	            var verifier = com.auth0.jwt.JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256(JWT_SECRET)).build();
	            var decoded = verifier.verify(token);
	            // Extract the list of roles from the JWT
	            List<String> roles = decoded.getClaim("roles").asList(String.class);
	            
	            // Attach to context so specific routes can check them
	            ctx.attribute("userRoles", roles);
	            ctx.attribute("username", decoded.getClaim("username").asString());
	        } catch (Exception e) {
	            throw new io.javalin.http.UnauthorizedResponse("Invalid Token");
	        }
	    });
		
	    routes.get("/health", this.openDuckApiController::handleHealthCheck);
	    
	    // Example: Manage the server (e.g., check metadata)
	    routes.get("/management/databases", ctx -> {
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
	    });

	    routes.get("/management/schemas", ctx -> {
	        List<Map<String, Object>> databases = new ArrayList<>();
	        
	        List<String> roles = ctx.attribute("userRoles");
	        
	        if (roles == null || !roles.contains("admin")) {
	            throw new io.javalin.http.ForbiddenResponse("You need the ADMIN role to perform this action.");
	        }
	        
	        // Query the DuckDB catalog
	        try (Statement stmt = this.duckdb.createStatement();
	             ResultSet rs = stmt.executeQuery("SELECT * FROM duckdb_schemas();")) {
	            
	            while (rs.next()) {
	                databases.add(Map.of(
	                    "oid", rs.getLong("oid"),
	                    "database_name", rs.getString("database_name"),
	                    "database_oid", rs.getLong("database_oid"),
	                    "schema_name", rs.getString("schema_name"),
	                    "comment", rs.getString("comment") != null ? rs.getString("comment") : "",
	                    "tags", rs.getObject("tags") != null ? rs.getObject("tags") : Collections.emptyMap(),
	                    "internal", rs.getBoolean("internal"),
	                    "sql", rs.getString("sql") != null ? rs.getString("sql") : ""
	                ));
	            }
	            
	            ctx.json(databases);
	        } catch (SQLException e) {
	            logger.error("Failed to fetch databases", e);
	            ctx.status(500).result("Internal Server Error: " + e.getMessage());
	        }
	    });
	    
	    routes.get("/management/users", ctx -> {
	        List<Map<String, Object>> databases = new ArrayList<>();
	        
	        List<String> roles = ctx.attribute("userRoles");
	        
	        if (roles == null || !roles.contains("admin")) {
	            throw new io.javalin.http.ForbiddenResponse("You need the ADMIN role to perform this action.");
	        }
	        
	        // Query the DuckDB catalog
	        try (Statement stmt = this.metadatadb.createStatement();
	             ResultSet rs = stmt.executeQuery("SELECT * FROM openduck_users;")) {
	            
	            while (rs.next()) {
	                databases.add(Map.of(
	                    "id", rs.getObject("id").toString(),
	                    "username", rs.getString("username"),
	                    "role", rs.getString("role") != null ? rs.getString("role") : "USER",
	                    "created_at", rs.getTimestamp("created_at").toString()
	                ));
	            }
	            
	            ctx.json(databases);
	        } catch (SQLException e) {
	            logger.error("Failed to fetch databases", e);
	            ctx.status(500).result("Internal Server Error: " + e.getMessage());
	        }
	    });        
	    
	    // Example: Shutdown via API
	    routes.post("/management/shutdown", ctx -> {
	        ctx.result("Shutting down...");
	        System.exit(0);
	    });
	}
	
	private User authenticateUser(String username, String password) {

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

	

	
	public void start() throws Exception {
		logger.info("Starting OpenDuck Server");
		server.start();
		System.out.println("OpenDuck Server started at " + server.getLocation());
		logger.info("OpenDuck Server started at " + server.getLocation());
		
		// Start REST Server (use a different port from Config)
        
        restServer.start(this.restPort);
        
        System.out.println("REST API started at http://localhost:" + this.restPort);
		
		
	}

	@Override
	public void close() throws Exception {
		logger.info("Stopping OpenDuck Server");
		this.server.close();
		this.duckdb.close();
		this.restServer.stop();
		logger.info("OpenDuck Server stopped");
	}

	// ============================
	// FlightProducer interface
	// ============================

	@Override
	public FlightInfo getFlightInfo(CallContext context, FlightDescriptor descriptor) {
		// Arrow 18.3.0 has no getType(), check command directly
		if (descriptor.getCommand() == null) {
			throw CallStatus.INVALID_ARGUMENT.withDescription("Only SQL command descriptors are supported")
					.toRuntimeException();
		}

		String sql = new String(descriptor.getCommand(), StandardCharsets.UTF_8);

		try {
			Schema schema = DuckDBArrowUtil.getSchema(duckdb, sql);
			Ticket ticket = new Ticket(descriptor.getCommand());

			return new FlightInfo(schema, descriptor,
					Collections.singletonList(new FlightEndpoint(ticket, server.getLocation())), -1, -1);
		} catch (Exception e) {
			logger.error(e);
			throw CallStatus.INTERNAL.withCause(e).withDescription(e.getMessage()).toRuntimeException();
		}
	}

	@Override
	public void getStream(CallContext context, Ticket ticket, ServerStreamListener listener) {
		String sql = new String(ticket.getBytes(), StandardCharsets.UTF_8);
		try {
			DuckDBArrowUtil.streamQuery(duckdb, sql, listener);
		} catch (Exception e) {
			logger.error(e);
			listener.error(CallStatus.INTERNAL.withCause(e).withDescription(e.getMessage()).toRuntimeException());
		}
	}

	// ============================
	// Unused FlightProducer methods
	// ============================

	@Override
	public void listFlights(CallContext context, Criteria criteria, StreamListener<FlightInfo> listener) {
		// logger.error("listFlights not supported");
		throw new UnsupportedOperationException("listFlights not supported");
	}

	@Override
	public SchemaResult getSchema(CallContext context, FlightDescriptor descriptor) {
		// You must wrap the Schema in a SchemaResult
		// Usually, the SQL command is sent in the 'cmd' field of the descriptor
		String sql = new String(descriptor.getCommand());

		try (Connection conn = this.duckdb) {
			Schema schema = getSchemaFromSql(conn, sql, this.allocator);
			return new SchemaResult(schema);
		} catch (SQLException e) {
			logger.error(e.getStackTrace());
			throw CallStatus.INTERNAL.withCause(e).withDescription("SQL Error").toRuntimeException();
		}
	}
//    @Override
//    public Schema getSchema(CallContext context, FlightDescriptor descriptor) {
//        throw new UnsupportedOperationException("getSchema not supported");
//    }

	@Override
	public void doAction(CallContext context, Action action, StreamListener<Result> listener) {
		// logger.error("doAction not supported");
		throw new UnsupportedOperationException("doAction not supported");
	}

	@Override
	public void listActions(CallContext context, StreamListener<ActionType> listener) {
		// logger.error("listActions not supported");
		throw new UnsupportedOperationException("listActions not supported");
	}

	@Override
	public Runnable acceptPut(CallContext context, FlightStream flightStream, StreamListener<PutResult> ackStream) {
		// logger.error("acceptPut not supported");
		throw new UnsupportedOperationException("acceptPut not supported");
	}

	// TODO: Change to RootAllocator
	public Schema getSchemaFromSql(Connection conn, String sql, BufferAllocator allocator) throws SQLException {
		// 1. Prepare the statement but don't fetch all data yet
		try (PreparedStatement stmt = conn.prepareStatement(sql)) {

			// 2. Get the Metadata from the database
			ResultSetMetaData metadata = stmt.getMetaData();

			// 3. Create a configuration for the conversion
			JdbcToArrowConfig config = new JdbcToArrowConfigBuilder().setAllocator(allocator)
					.setReuseVectorSchemaRoot(true).setTargetBatchSize(1024) // Smaller batches can sometimes avoid
																				// buffer overflows
					.build();

			// 4. Let Arrow translate JDBC types to Arrow types
			return JdbcToArrowUtils.jdbcToArrowSchema(metadata, config);
		}
	}
}
