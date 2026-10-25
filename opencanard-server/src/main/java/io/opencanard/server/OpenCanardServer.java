package io.opencanard.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;

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
import org.duckdb.DuckDBConnection;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import io.grpc.internal.LogExceptionRunnable;
import io.opencanard.auth.OpenCanardAuthenticator;
import io.opencanard.conf.Config;
import io.opencanard.rest.RestApiServer;
import io.opencanard.user.UserDatabases;
import io.opencanard.user.UserService;
import io.opencanard.util.DuckDBArrowUtil;

public class OpenCanardServer implements FlightProducer, AutoCloseable {

	private final Connection duckdb;
	private final Connection metadatadb;
	private final FlightServer server;
	private final BufferAllocator allocator;
	private final RestApiServer restApi; // REST API, see io.opencanard.rest
	// One DuckDB file per user created through the API: <data.dir>/users/<login>.duckdb
	private final UserDatabases userDatabases;
	
	
	
	private static final Logger logger = LogManager.getLogger(OpenCanardServer.class);

	
    public static final String CONF_DIR;
    public static final String DATA_DIR;
    public static final String LOG_DIR;
    public static final String METADATA_DIR;

    // Credentials of the admin user created when the metadata database is first created.
    // Private on purpose: the password must not be readable from outside this class.
    private static final String ADMIN_USER;
    private static final String ADMIN_PASSWORD;

    static {
    	System.out.println("Loading properties");
        CONF_DIR = require("conf.dir");
        DATA_DIR = require("data.dir");
        LOG_DIR = require("log.dir");
        METADATA_DIR = require("metadata.dir");
        ADMIN_USER = require("admin.user");
        ADMIN_PASSWORD = require("admin.password");
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
		
		logger.info("CONF DIR = " + CONF_DIR);
		logger.info("DATA DIR = " + DATA_DIR);
		logger.info("METADATA DIR = " + METADATA_DIR);
		logger.info("LOG DIR = " + LOG_DIR);
		
		Path opencanarddb = Paths.get(DATA_DIR, Config.get("opencanard.db")).toAbsolutePath().normalize();		
		//Path metadatadb = Paths.get(METADATA_DIR, Config.get("metadatadb.db")).toAbsolutePath().normalize();
		
		try (BufferAllocator rootAllocator = new RootAllocator(Config.getInt("arrow.memory.mb") * 1024L * 1024L)) {
			// You now have a BufferAllocator instance!
			// By default, it has no limit, but you can set one:
			// new RootAllocator(1024 * 1024 * 1024L); // 1GB Limit
			OpenCanardServer server = new OpenCanardServer(Config.get("opencanard.host"), Config.getInt("opencanard.port"),
					METADATA_DIR.toString().replace("\\", "/"), opencanarddb.toString().replace("\\", "/"), rootAllocator);

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

	public OpenCanardServer(String host, int arrowPort, String metadataPath, String duckdbPath, BufferAllocator allocator)
			throws Exception {
		logger.error("****************************** " + metadataPath); 

		// metadataPath is the full path of the FILE: <metadata.dir>/metadata.duckdb
		//Path metadataFile = Paths.get(metadataPath).toAbsolutePath().normalize();
		logger.error("Checking directory A");
		Path metadataFolder = Paths.get(METADATA_DIR).toAbsolutePath().normalize();
		// Only the parent folder is created, never the file path itself
		Files.createDirectories(metadataFolder);
		logger.error("Checking directory");
		try (var paths = Files.list(metadataFolder)) {
			
			paths.forEach(path -> logger.error("Directory entry: {}", path));
		} catch (IOException e) {
			logger.error("Failed to list directory: {}", metadataFolder, e);
		}

		Path metadataFile = Paths.get(METADATA_DIR, Config.get("metadatadb.db")).toAbsolutePath().normalize();
		// Idempotent: creates the file, the opencanard_users table and the admin user only if missing
		createMetadataDb(metadataFile);


		// Embedded DuckDB
		try {
			this.duckdb = DriverManager.getConnection("jdbc:duckdb:" + duckdbPath);
			this.metadatadb = DriverManager.getConnection("jdbc:duckdb:" + metadataFile);
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
		this.server = FlightServer.builder(this.allocator, location, this).headerAuthenticator(new OpenCanardAuthenticator(this.metadatadb)).build();
		//this.server = FlightServer.builder(this.allocator, location, this).headerAuthenticator(CallHeaderAuthenticator.NO_OP).build(); 
		
		// REST API (Javalin): all endpoints live in the io.opencanard.rest package
		this.userDatabases = new UserDatabases(Paths.get(DATA_DIR, "users"));
		UserService userService = new UserService(this.metadatadb, this.userDatabases);
		this.restApi = new RestApiServer(this.duckdb, this.metadatadb, userService,
				Config.getInt("opencanard.rest.port"));
	}

	/**
	 * Makes sure the metadata database is ready. Safe to call on every start:
	 * <ul>
	 * <li>file missing: the whole database is built in a temporary file and moved to its final
	 * name only when complete, so metadata.duckdb never exists half-initialised;</li>
	 * <li>file present: it is opened as it is and the opencanard_users table is created only if
	 * missing;</li>
	 * <li>the admin user (admin.user / admin.password, BCrypt-hashed like PasswordManager does)
	 * is inserted only if that username is not already in the table. An existing user, and its
	 * password, are never modified.</li>
	 * </ul>
	 */
	private void createMetadataDb(Path metadataFile) throws Exception {
		long t0 = System.currentTimeMillis();
		boolean fileExists = Files.exists(metadataFile);
		Path tmpFile = metadataFile.resolveSibling(metadataFile.getFileName() + ".tmp");
		Path tmpWal = metadataFile.resolveSibling(metadataFile.getFileName() + ".tmp.wal");
		// New database: work on the temp file. Existing database: work on it directly.
		Path workFile = fileExists ? metadataFile : tmpFile;

		if (fileExists) {
			logger.info("[metadata-init] {} exists, checking its content", metadataFile);
		} else {
			logger.info("[metadata-init] {} not found, creating it through {}", metadataFile, tmpFile);
			// Remove leftovers of an earlier interrupted attempt
			boolean removedTmp = Files.deleteIfExists(tmpFile);
			boolean removedWal = Files.deleteIfExists(tmpWal);
			logger.info("[metadata-init] leftovers removed (tmp={}, tmp.wal={})", removedTmp, removedWal);
		}

		try {
			String workUrl = "jdbc:duckdb:" + workFile.toAbsolutePath().toString().replace("\\", "/");
			try (Connection conn = DriverManager.getConnection(workUrl)) {
				logger.info("[metadata-init] connected to {}", workUrl);

				// 1. opencanard_users table: create only if it does not exist
				boolean tableExists;
				try (Statement stmt = conn.createStatement();
						ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
								+ "WHERE table_schema = 'main' AND table_name = 'opencanard_users'")) {
					tableExists = rs.next() && rs.getInt(1) > 0;
				}
				if (tableExists) {
					logger.info("[metadata-init] table opencanard_users already exists, skipping creation");
				} else {
					try (Statement stmt = conn.createStatement()) {
						stmt.execute("CREATE TABLE opencanard_users(username VARCHAR PRIMARY KEY, "
								+ "password_hash VARCHAR NOT NULL, \"role\" VARCHAR NOT NULL, "
								+ "created_at TIMESTAMP NOT NULL DEFAULT now())");
					}
					logger.info("[metadata-init] table opencanard_users created");
				}

				// 2. admin user: insert only if the username is not already in the table
				boolean userExists;
				try (PreparedStatement ps = conn
						.prepareStatement("SELECT COUNT(*) FROM opencanard_users WHERE username = ?")) {
					ps.setString(1, ADMIN_USER);
					try (ResultSet rs = ps.executeQuery()) {
						userExists = rs.next() && rs.getInt(1) > 0;
					}
				}
				if (userExists) {
					logger.info("[metadata-init] user '{}' already exists, skipping insert (password left untouched)",
							ADMIN_USER);
				} else {
					// The password is never logged, only the hash algorithm prefix and length
					String passwordHash = new BCryptPasswordEncoder().encode(ADMIN_PASSWORD);
					logger.info("[metadata-init] password hashed with BCrypt (prefix={}, length={})",
							passwordHash.substring(0, 7), passwordHash.length());
					try (PreparedStatement ps = conn.prepareStatement(
							"INSERT INTO opencanard_users(username, password_hash, \"role\", created_at) VALUES (?, ?, ?, now())")) {
						ps.setString(1, ADMIN_USER);
						ps.setString(2, passwordHash);
						ps.setString(3, "admin");
						int rows = ps.executeUpdate();
						logger.info("[metadata-init] user '{}' inserted with role 'admin' (rows affected: {})",
								ADMIN_USER, rows);
					}
				}

				// Flush everything into the main file so no .wal is left behind
				try (Statement stmt = conn.createStatement()) {
					stmt.execute("CHECKPOINT");
					logger.info("[metadata-init] CHECKPOINT done");
				}
			}

			if (!fileExists) {
				logger.info("[metadata-init] temporary database closed ({} bytes, tmp.wal exists: {})",
						Files.size(tmpFile), Files.exists(tmpWal));
				Files.move(tmpFile, metadataFile, StandardCopyOption.ATOMIC_MOVE);
				logger.info("[metadata-init] {} moved to {}", tmpFile.getFileName(), metadataFile);
			}

			// Final check: reopen the file and read the users back
			String finalUrl = "jdbc:duckdb:" + metadataFile.toAbsolutePath().toString().replace("\\", "/");
			int users = 0;
			boolean adminFound = false;
			try (Connection conn = DriverManager.getConnection(finalUrl);
					Statement stmt = conn.createStatement();
					ResultSet rs = stmt.executeQuery(
							"SELECT username, \"role\", created_at FROM opencanard_users ORDER BY username")) {
				while (rs.next()) {
					users++;
					String username = rs.getString("username");
					adminFound |= ADMIN_USER.equals(username);
					logger.info("[metadata-init] verified user '{}' role '{}' created_at {}", username,
							rs.getString("role"), rs.getTimestamp("created_at"));
				}
			}
			if (!adminFound) {
				throw new IllegalStateException("User '" + ADMIN_USER + "' not found in opencanard_users after setup");
			}
			logger.info("[metadata-init] DONE: {} ({} bytes, {} user(s)) in {} ms", metadataFile,
					Files.size(metadataFile), users, System.currentTimeMillis() - t0);
		} catch (Exception e) {
			logger.error("[metadata-init] FAILED after {} ms", System.currentTimeMillis() - t0, e);
			// Only the temporary files are cleaned up: an existing metadata.duckdb is never deleted
			if (!fileExists) {
				Files.deleteIfExists(tmpFile);
				Files.deleteIfExists(tmpWal);
			}
			throw e;
		}
	}

	public void start() throws Exception {
		logger.info("Starting OpenCanard Server");
		server.start();
		System.out.println("OpenCanard Server started at " + server.getLocation());
		logger.info("OpenCanard Server started at " + server.getLocation());
		
		// Start REST Server
		restApi.start();
	}

	@Override
	public void close() throws Exception {
		logger.info("Stopping OpenCanard Server");
		this.server.close();
		this.userDatabases.close();
		this.duckdb.close();
		this.restApi.stop();
		logger.info("OpenCanard Server stopped");
	}

	/**
	 * A connection for one Flight call, which the caller closes. The caller is identified by the login
	 * the authenticator put in the call context:
	 * <ul>
	 * <li>users created through the API have their own database file ({@code <data.dir>/users/<login>.duckdb})
	 * and always get a connection to it;</li>
	 * <li>users without one (for example the admin created at the first start) keep using the shared
	 * database, as before.</li>
	 * </ul>
	 * Closing a connection returned here never closes the underlying database.
	 */
	private Connection connectionFor(CallContext context) throws SQLException {
		String login = context.peerIdentity();
		Connection personal = userDatabases.connect(login);
		if (personal != null) {
			logger.debug("Flight call of '{}' uses its own database", login);
			return personal;
		}
		logger.debug("Flight call of '{}' uses the shared database", login);
		return ((DuckDBConnection) this.duckdb).duplicate();
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

		try (Connection conn = connectionFor(context)) {
			Schema schema = DuckDBArrowUtil.getSchema(conn, sql);
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
		try (Connection conn = connectionFor(context)) {
			DuckDBArrowUtil.streamQuery(conn, sql, listener);
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

		try (Connection conn = connectionFor(context)) {
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