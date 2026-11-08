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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;


import io.opencanard.flightsql.FlightSqlCommands;
import io.opencanard.flightsql.OpenCanardFlightSqlProducer;
import io.opencanard.auth.FlightAuthenticator;
import io.opencanard.conf.Config;
import io.opencanard.rest.RestApiServer;
import io.opencanard.user.DatabaseRegistry;
import io.opencanard.storage.SecretBox;
import io.opencanard.storage.StorageRepository;
import io.opencanard.storage.StorageService;
import io.opencanard.user.UserConnections;
import io.opencanard.user.UserDatabases;
import io.opencanard.user.RoleRepository;
import io.opencanard.user.RoleService;
import io.opencanard.user.UserRepository;
import io.opencanard.user.UserService;
import io.opencanard.query.RunningQueries;
import io.opencanard.util.DuckDBArrowUtil;

public class OpenCanardServer implements FlightProducer, AutoCloseable {

	private final Connection duckdb;
	private final Connection metadatadb;
	private final FlightServer server;
	private final BufferAllocator allocator;
	private final RestApiServer restApi; // REST API, see io.opencanard.rest
	// One DuckDB file per user created through the API: <data.dir>/users/<login>.duckdb
	private final UserDatabases userDatabases;
	private final UserConnections userConnections;
	// Queries running now, from Flight and the web console, so they can be listed and cancelled
	private final RunningQueries runningQueries = new RunningQueries();
	// Arrow Flight SQL requests (ADBC, Flight SQL JDBC/ODBC drivers) go here; raw SQL commands stay in this class
	private final OpenCanardFlightSqlProducer flightSql;
	
	
	
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

		// Arrow results come from the DuckDB JDBC driver itself (arrowExportStream, the Arrow C data interface), which
		// does not need DuckDB's "arrow" extension. That extension is not published for DuckDB 2.0 (its download
		// answers 404), so a failure to install it is only logged; older installations that have it keep loading it.
		loadArrowExtension(this.duckdb, "the main database");
		loadArrowExtension(this.metadatadb, "the metadata database");
		// Flight gRPC location
		Location location = Location.forGrpcInsecure(host, arrowPort);

		// Build server
		this.allocator = allocator;
	//	this.server = FlightServer.builder(this.allocator, location, this).middleware(FlightServerMiddleware.Key.of("auth"),new AuthMiddlewareFactory("mysecret123")).build();
		// Basic credentials (checked against opencanard_users), the Bearer token handed out after a Basic call, or
		// the JWT of the REST login; anything else is UNAUTHENTICATED. See FlightAuthenticator.
		this.server = FlightServer.builder(this.allocator, location, this)
				.headerAuthenticator(new FlightAuthenticator(this.metadatadb))
				.build();
		//this.server = FlightServer.builder(this.allocator, location, this).headerAuthenticator(CallHeaderAuthenticator.NO_OP).build(); 
		
		// REST API (Javalin): all endpoints live in the io.opencanard.rest package
		this.userDatabases = new UserDatabases(Paths.get(DATA_DIR, "users"), this.metadatadb);
		this.userDatabases.reconcile();
		UserService userService = new UserService(this.metadatadb, this.userDatabases, ADMIN_USER);
		StorageService storageService;
		try {
			// key: env OPENCANARD_STORAGE_KEY, property opencanard.storage.key, or the file metadata/storage.key
			// (created on first start; conf/storage.key of earlier versions is still honored)
			String configuredKey = System.getenv("OPENCANARD_STORAGE_KEY");
			if (configuredKey == null || configuredKey.isBlank()) {
				configuredKey = Config.get("opencanard.storage.key");
			}
			storageService = new StorageService(this.metadatadb, SecretBox.open(configuredKey,
					Paths.get(METADATA_DIR, "storage.key"), Paths.get(CONF_DIR, "storage.key")));
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Cannot read or create the storage key in " + METADATA_DIR, e);
		}
		this.userConnections = new UserConnections(this.metadatadb, this.userDatabases, storageService);
		this.flightSql = new OpenCanardFlightSqlProducer(this.userConnections, this.runningQueries, this.allocator,
				serverVersion());
		this.restApi = new RestApiServer(this.duckdb, this.metadatadb, userService, this.userConnections,
				storageService, this.runningQueries, Config.getInt("opencanard.rest.port"));
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
						stmt.execute(UserRepository.CREATE_TABLE);
					}
					logger.info("[metadata-init] table opencanard_users created");
				}

				// 1a. roles: opencanard_roles (name, description) and opencanard_user_roles (user -> roles), with the
				// built-in roles; users of earlier versions had one "role" column, moved here and then dropped
				migrateRoles(conn);

				// 1b. opencanard_databases table (login -> duckdb file): create only if it does not exist
				boolean dbTableExists;
				try (Statement stmt = conn.createStatement();
						ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
								+ "WHERE table_schema = 'main' AND table_name = 'opencanard_databases'")) {
					dbTableExists = rs.next() && rs.getInt(1) > 0;
				}
				if (dbTableExists) {
					logger.info("[metadata-init] table opencanard_databases already exists, skipping creation");
				} else {
					try (Statement stmt = conn.createStatement()) {
						stmt.execute(DatabaseRegistry.CREATE_TABLE);
					}
					logger.info("[metadata-init] table opencanard_databases created");
				}

				// 1c. opencanard_storages table (S3 storages of every user): create only if it does not exist
				boolean storageTableExists;
				try (Statement stmt = conn.createStatement();
						ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
								+ "WHERE table_schema = 'main' AND table_name = 'opencanard_storages'")) {
					storageTableExists = rs.next() && rs.getInt(1) > 0;
				}
				if (storageTableExists) {
					logger.info("[metadata-init] table opencanard_storages already exists, skipping creation");
				} else {
					try (Statement stmt = conn.createStatement()) {
						stmt.execute(StorageRepository.CREATE_TABLE);
					}
					logger.info("[metadata-init] table opencanard_storages created");
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
					new UserRepository().insert(conn, ADMIN_USER, passwordHash);
					logger.info("[metadata-init] user '{}' inserted", ADMIN_USER);
				}
				// The built-in admin user always has the admin role, also if it was taken away by hand
				try (PreparedStatement ps = conn.prepareStatement("INSERT INTO opencanard_user_roles(username, role_name) "
						+ "SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM opencanard_user_roles WHERE username = ? AND role_name = ?)")) {
					ps.setString(1, ADMIN_USER);
					ps.setString(2, RoleService.ADMIN);
					ps.setString(3, ADMIN_USER);
					ps.setString(4, RoleService.ADMIN);
					if (ps.executeUpdate() > 0) {
						logger.info("[metadata-init] role 'admin' given to user '{}'", ADMIN_USER);
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
					ResultSet rs = stmt.executeQuery("SELECT u.username, u.created_at, "
							+ "(SELECT string_agg(r.role_name, ', ' ORDER BY r.role_name) FROM opencanard_user_roles r "
							+ "WHERE r.username = u.username) AS roles FROM opencanard_users u ORDER BY u.username")) {
				while (rs.next()) {
					users++;
					String username = rs.getString("username");
					adminFound |= ADMIN_USER.equals(username);
					logger.info("[metadata-init] verified user '{}' roles [{}] created_at {}", username,
							rs.getString("roles"), rs.getTimestamp("created_at"));
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

	/**
	 * Creates the roles tables when missing, adds the built-in roles and moves the single {@code role} column of
	 * {@code opencanard_users} (earlier versions) into {@code opencanard_user_roles}, then drops the column. Idempotent.
	 */
	private static void migrateRoles(Connection conn) throws SQLException {
		if (!tableExists(conn, "opencanard_roles")) {
			try (Statement stmt = conn.createStatement()) {
				stmt.execute(RoleRepository.CREATE_ROLES_TABLE);
			}
			logger.info("[metadata-init] table opencanard_roles created");
		}
		if (!tableExists(conn, "opencanard_user_roles")) {
			try (Statement stmt = conn.createStatement()) {
				stmt.execute(RoleRepository.CREATE_USER_ROLES_TABLE);
			}
			logger.info("[metadata-init] table opencanard_user_roles created");
		}
		RoleRepository roles = new RoleRepository();
		roles.insertIfMissing(conn, RoleService.ADMIN, "Administrators: manage users and roles, work on the metadata database");
		roles.insertIfMissing(conn, RoleService.USER, "Regular users: work on their own database");

		boolean hasRoleColumn;
		try (Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
						+ "WHERE table_schema = 'main' AND table_name = 'opencanard_users' AND column_name = 'role'")) {
			hasRoleColumn = rs.next() && rs.getInt(1) > 0;
		}
		if (!hasRoleColumn) {
			return;
		}
		logger.info("[metadata-init] moving opencanard_users.role into opencanard_user_roles");
		boolean autoCommit = conn.getAutoCommit();
		conn.setAutoCommit(false);
		try (Statement stmt = conn.createStatement()) {
			int newRoles = stmt.executeUpdate("INSERT INTO opencanard_roles(name, description) "
					+ "SELECT DISTINCT u.\"role\", 'Migrated from the role column' FROM opencanard_users u "
					+ "WHERE u.\"role\" IS NOT NULL AND u.\"role\" <> '' "
					+ "AND NOT EXISTS (SELECT 1 FROM opencanard_roles r WHERE r.name = u.\"role\")");
			int assigned = stmt.executeUpdate("INSERT INTO opencanard_user_roles(username, role_name) "
					+ "SELECT u.username, u.\"role\" FROM opencanard_users u "
					+ "WHERE u.\"role\" IS NOT NULL AND u.\"role\" <> '' AND NOT EXISTS (SELECT 1 FROM opencanard_user_roles x "
					+ "WHERE x.username = u.username AND x.role_name = u.\"role\")");
			stmt.execute("ALTER TABLE opencanard_users DROP COLUMN \"role\"");
			conn.commit();
			logger.info("[metadata-init] {} role(s) added, {} assignment(s) moved, column role dropped", newRoles,
					assigned);
		} catch (SQLException e) {
			conn.rollback();
			throw e;
		} finally {
			conn.setAutoCommit(autoCommit);
		}
	}

	private static boolean tableExists(Connection conn, String table) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM information_schema.tables "
				+ "WHERE table_schema = 'main' AND table_name = ?")) {
			ps.setString(1, table);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() && rs.getInt(1) > 0;
			}
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
		this.flightSql.close();
		this.userDatabases.close();
		this.duckdb.close();
		this.restApi.stop();
		logger.info("OpenCanard Server stopped");
	}

	/**
	 * A connection for one Flight call, which the caller closes. The caller is identified by the login the
	 * authenticator put in the call context; {@link UserConnections} decides the database (admins: metadata,
	 * everyone else: the file registered in {@code opencanard_databases}, otherwise the call is refused).
	 */
	private Connection connectionFor(CallContext context) throws SQLException {
		return userConnections.open(context.peerIdentity());
	}

	private static void loadArrowExtension(Connection conn, String which) {
		try (Statement stmt = conn.createStatement()) {
			stmt.execute("INSTALL arrow;");
			stmt.execute("LOAD arrow;");
			logger.info("arrow extension loaded in {}", which);
		} catch (SQLException e) {
			logger.warn("DuckDB arrow extension not available in {} ({}); not needed for Arrow Flight", which,
					e.getMessage());
		}
	}

	/** The version of the server jar (Implementation-Version), or "dev" when run from the IDE. */
	private static String serverVersion() {
		String v = OpenCanardServer.class.getPackage().getImplementationVersion();
		return v == null ? "dev" : v;
	}

	/** A query stopped through {@link RunningQueries#cancel} (SQLSTATE 57014, "query canceled"). */
	private static boolean isCancelled(Exception e) {
		return e instanceof SQLException && "57014".equals(((SQLException) e).getSQLState());
	}

	// ============================
	// FlightProducer interface
	// ============================
	// Two protocols share the port. A command, ticket or DoPut descriptor holding a Flight SQL protobuf message
	// goes to OpenCanardFlightSqlProducer; anything else is OpenCanard's own protocol, where the command and the
	// ticket are the SQL text (the bundled JDBC driver, the DBeaver plugin, plain PyArrow code).

	@Override
	public FlightInfo getFlightInfo(CallContext context, FlightDescriptor descriptor) {
		// Arrow 18.3.0 has no getType(), check command directly
		if (descriptor.getCommand() == null) {
			throw CallStatus.INVALID_ARGUMENT.withDescription("Only SQL command descriptors are supported")
					.toRuntimeException();
		}
		if (FlightSqlCommands.isFlightSql(descriptor.getCommand())) {
			return flightSql.getFlightInfo(context, descriptor);
		}

		String sql = new String(descriptor.getCommand(), StandardCharsets.UTF_8);

		try (Connection conn = connectionFor(context)) {
			DuckDBArrowUtil.Outcome outcome;
			// finish() runs before the connection is closed: a cancel must never reach a closed connection
			RunningQueries.Query query = runningQueries.start(context.peerIdentity(), RunningQueries.Source.FLIGHT,
					sql, null);
			try {
				outcome = DuckDBArrowUtil.run(conn, sql, query);
			} finally {
				runningQueries.finish(query);
			}
			if (!outcome.returnsRows()) {
				// CREATE / INSERT / UPDATE / DELETE...: already executed, nothing to stream. No endpoints and
				// the number of affected rows in "records" tell the client it is an update.
				logger.debug("Statement executed without a result set, {} row(s) affected", outcome.getUpdateCount());
				return new FlightInfo(new Schema(Collections.emptyList()), descriptor, Collections.emptyList(), -1,
						outcome.getUpdateCount());
			}
			Ticket ticket = new Ticket(descriptor.getCommand());

			return new FlightInfo(outcome.getSchema(), descriptor,
					Collections.singletonList(new FlightEndpoint(ticket, server.getLocation())), -1, -1);
		} catch (Exception e) {
			if (isCancelled(e)) {
				throw CallStatus.CANCELLED.withDescription(e.getMessage()).toRuntimeException();
			}
			logger.error(e);
			throw CallStatus.INTERNAL.withCause(e).withDescription(e.getMessage()).toRuntimeException();
		}
	}

	@Override
	public void getStream(CallContext context, Ticket ticket, ServerStreamListener listener) {
		if (FlightSqlCommands.isFlightSql(ticket.getBytes())) {
			flightSql.getStream(context, ticket, listener);
			return;
		}
		String sql = new String(ticket.getBytes(), StandardCharsets.UTF_8);
		try (Connection conn = connectionFor(context)) {
			RunningQueries.Query query = runningQueries.start(context.peerIdentity(), RunningQueries.Source.FLIGHT,
					sql, null);
			try {
				DuckDBArrowUtil.streamQuery(conn, sql, listener, query);
			} finally {
				runningQueries.finish(query);
			}
		} catch (Exception e) {
			if (isCancelled(e)) {
				listener.error(CallStatus.CANCELLED.withDescription(e.getMessage()).toRuntimeException());
				return;
			}
			logger.error(e);
			listener.error(CallStatus.INTERNAL.withCause(e).withDescription(e.getMessage()).toRuntimeException());
		}
	}

	// ============================
	// Unused FlightProducer methods
	// ============================

	@Override
	public void listFlights(CallContext context, Criteria criteria, StreamListener<FlightInfo> listener) {
		flightSql.listFlights(context, criteria, listener);
	}

	@Override
	public SchemaResult getSchema(CallContext context, FlightDescriptor descriptor) {
		// You must wrap the Schema in a SchemaResult
		// Usually, the SQL command is sent in the 'cmd' field of the descriptor
		if (FlightSqlCommands.isFlightSql(descriptor.getCommand())) {
			return flightSql.getSchema(context, descriptor);
		}
		String sql = new String(descriptor.getCommand(), StandardCharsets.UTF_8);

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

	// Actions and DoPut exist only in Flight SQL (prepared statements, cancel, updates)

	@Override
	public void doAction(CallContext context, Action action, StreamListener<Result> listener) {
		flightSql.doAction(context, action, listener);
	}

	@Override
	public void listActions(CallContext context, StreamListener<ActionType> listener) {
		flightSql.listActions(context, listener);
	}

	@Override
	public Runnable acceptPut(CallContext context, FlightStream flightStream, StreamListener<PutResult> ackStream) {
		return flightSql.acceptPut(context, flightStream, ackStream);
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