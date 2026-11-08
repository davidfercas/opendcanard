package io.opencanard.user;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBConnection;

/**
 * Manages the personal DuckDB files of the users, stored in {@code <dir>} and listed in the table
 * {@code opencanard_databases} of the metadata database (see {@link DatabaseRegistry}).
 * <ul>
 * <li>{@link #create} builds a new, empty database file for a user;</li>
 * <li>{@link #connect} looks the login up in the registry, opens (once per file, lazily) the registered
 * file and hands out a new connection to it for every call, so concurrent requests never share a JDBC
 * connection. The caller closes the connection it gets; that does not close the database;</li>
 * <li>{@link #reconcile} runs at startup and makes sure every non-admin user has a registered database.</li>
 * </ul>
 * A database file is never created by {@link #connect}: DuckDB creates a file just by connecting to a
 * path, so existence is checked first.
 */
public class UserDatabases implements AutoCloseable {

	private static final Logger logger = LogManager.getLogger(UserDatabases.class);

	private final Path dir;
	private final Connection metadatadb;
	private final DatabaseRegistry registry = new DatabaseRegistry();
	/** One open "base" connection per database file; it keeps the database open between requests. */
	private final Map<String, DuckDBConnection> open = new HashMap<>();
	/** The access mode each entry of {@link #open} was opened with (true: {@code access_mode=READ_ONLY}). */
	private final Map<String, Boolean> openReadOnly = new HashMap<>();

	public UserDatabases(Path dir, Connection metadatadb) throws IOException {
		this.metadatadb = metadatadb;
		this.dir = dir.toAbsolutePath().normalize();
		Files.createDirectories(this.dir);
		logger.info("[user-db] user databases are stored in {}", this.dir);
	}

	public Path databaseFile(String username) {
		return UsernameValidator.databaseFile(dir, username);
	}

	/** The name stored in {@code opencanard_databases.duckdb_file} for the default database of a user. */
	public static String fileName(String username) {
		return username + ".duckdb";
	}

	public boolean exists(String username) {
		return Files.exists(databaseFile(username));
	}

	/**
	 * Creates an empty database for the user. It is built in a temporary file and moved into place when
	 * complete, so a user's file never exists half-written.
	 *
	 * @throws FileAlreadyExistsException if the user already has a database file
	 */
	public synchronized void create(String username) throws Exception {
		Path file = databaseFile(username);
		if (Files.exists(file)) {
			throw new FileAlreadyExistsException(file.toString());
		}

		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Path tmpWal = file.resolveSibling(file.getFileName() + ".tmp.wal");
		try {
			Files.deleteIfExists(tmp);
			Files.deleteIfExists(tmpWal);

			try (Connection conn = DriverManager.getConnection(url(tmp)); Statement stmt = conn.createStatement()) {
				stmt.execute("CHECKPOINT");
			}
			logger.info("[user-db] created {} ({} bytes), moving it to {}", tmp.getFileName(), Files.size(tmp), file);

			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
			logger.info("[user-db] database of '{}' is ready: {}", username, file);
		} catch (Exception e) {
			logger.error("[user-db] failed to create the database of '{}'", username, e);
			Files.deleteIfExists(tmp);
			Files.deleteIfExists(tmpWal);
			throw e;
		}
	}

	/** Closes and removes the user's database file (used to roll back a failed user creation). */
	public synchronized void delete(String username) throws Exception {
		Path file = databaseFile(username);
		DuckDBConnection base = open.remove(file.toString());
		openReadOnly.remove(file.toString());
		if (base != null) {
			base.close();
		}
		boolean removed = Files.deleteIfExists(file);
		Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".wal"));
		logger.info("[user-db] database of '{}' removed (file existed: {})", username, removed);
	}

	/**
	 * Closes and removes a database file given by its name inside the user databases directory (as stored in
	 * {@code opencanard_databases.duckdb_file}), together with its {@code .wal}.
	 *
	 * @return true if the file existed
	 * @throws IOException if the name points outside the directory or the file cannot be removed (for example
	 *                     because a query still holds it open on Windows)
	 */
	public synchronized boolean deleteFile(String duckdbFile) throws IOException {
		Path file = dir.resolve(duckdbFile).normalize();
		if (!file.startsWith(dir) || file.equals(dir)) {
			throw new IOException("Refusing to remove '" + duckdbFile + "': it is outside " + dir);
		}
		DuckDBConnection base = open.remove(file.toString());
		openReadOnly.remove(file.toString());
		if (base != null) {
			try {
				base.close();
			} catch (SQLException e) {
				logger.warn("[user-db] could not close {} before removing it: {}", file, e.getMessage());
			}
		}
		boolean removed = Files.deleteIfExists(file);
		Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".wal"));
		logger.info("[user-db] removed {} (file existed: {})", file, removed);
		return removed;
	}

	/** {@link #connect(String, boolean)} in read-write mode. */
	public Connection connect(String login) throws SQLException {
		return connect(login, false);
	}

	/**
	 * A new connection to the database registered for the login in {@code opencanard_databases}, or
	 * {@code null} if the login has none. The caller must close it.
	 * <p>
	 * With {@code readOnly} the file is opened with {@code access_mode=READ_ONLY} (privilege
	 * {@link Privileges#READ_ONLY}). DuckDB fixes the access mode when it opens a file and refuses a second instance of
	 * the same file with another mode, so when the mode asked for differs from the one the file is open with, the
	 * file is closed and opened again. That only succeeds once the connections handed out before (running queries)
	 * are closed; until then the call fails, so a read-only user never gets a writable connection.
	 *
	 * @throws SQLException if the registered file is missing or lies outside the user databases directory, or the
	 *                      file is still in use with the other access mode
	 */
	public Connection connect(String login, boolean readOnly) throws SQLException {
		if (login == null) {
			return null;
		}
		DatabaseRegistry.Entry entry;
		try (Connection meta = ((DuckDBConnection) metadatadb).duplicate()) {
			entry = registry.find(meta, login);
		}
		if (entry == null) {
			return null;
		}

		Path file = dir.resolve(entry.getDuckdbFile()).normalize();
		if (!file.startsWith(dir)) {
			throw new SQLException("The database registered for '" + login + "' is outside " + dir);
		}

		DuckDBConnection base;
		String key = file.toString();
		synchronized (this) {
			base = open.get(key);
			if (base != null && openReadOnly.getOrDefault(key, false) != readOnly) {
				logger.info("[user-db] access mode of {} changes to {}, reopening it", file,
						readOnly ? "READ_ONLY" : "READ_WRITE");
				open.remove(key);
				openReadOnly.remove(key);
				try {
					base.close();
				} catch (SQLException e) {
					logger.warn("[user-db] could not close {} before reopening it: {}", file, e.getMessage());
				}
				base = null;
			}
			if (base == null) {
				if (Files.notExists(file)) {
					throw new SQLException("The database '" + entry.getDatabaseName() + "' of '" + login
							+ "' is registered as " + file + " but the file does not exist");
				}
				try {
					base = open(login, file, readOnly);
				} catch (SQLException e) {
					throw new SQLException("The database '" + entry.getDatabaseName() + "' of '" + login
							+ "' cannot be opened " + (readOnly ? "read-only" : "read-write")
							+ ". If its access mode was just changed, queries started before still hold it: try again "
							+ "when they finish (" + e.getMessage() + ")", e);
				}
				open.put(key, base);
				openReadOnly.put(key, readOnly);
			}
		}
		return base.duplicate();
	}

	/**
	 * Startup check of the registry:
	 * <ul>
	 * <li>every non-admin user without a registered database gets one: the existing {@code <login>.duckdb}
	 * is registered, or a new empty file is created and registered. Admins use the metadata database and
	 * get none;</li>
	 * <li>a registered file that does not exist is reported as an error (it is not recreated, its owner
	 * cannot connect until it is restored or the row removed).</li>
	 * </ul>
	 */
	public synchronized void reconcile() {
		logger.info("[user-db] checking opencanard_databases against opencanard_users and {}", dir);
		try (Connection meta = ((DuckDBConnection) metadatadb).duplicate()) {
			java.util.Set<String> registered = new java.util.HashSet<>();
			for (DatabaseRegistry.Entry e : registry.list(meta)) {
				registered.add(e.getLogin());
				Path file = dir.resolve(e.getDuckdbFile()).normalize();
				if (Files.exists(file)) {
					logger.info("[user-db] '{}' -> {} ({}) OK", e.getLogin(), e.getDuckdbFile(), e.getDatabaseName());
				} else {
					logger.error("[user-db] '{}' is registered with {} but {} does not exist", e.getLogin(),
							e.getDuckdbFile(), file);
				}
			}

			java.util.List<String> logins = new java.util.ArrayList<>();
			try (Statement st = meta.createStatement();
					java.sql.ResultSet rs = st.executeQuery(NON_ADMINS)) {
				while (rs.next()) {
					logins.add(rs.getString(1));
				}
			}
			for (String login : logins) {
				if (!registered.contains(login)) {
					ensureDatabase(meta, login);
				}
			}
		} catch (Exception e) {
			logger.error("[user-db] the registry check failed", e);
		}
	}

	/** Users without the role {@code admin} (roles are in {@code opencanard_user_roles}). */
	private static final String NON_ADMINS = "SELECT u.username FROM main.opencanard_users u WHERE NOT EXISTS ("
			+ "SELECT 1 FROM main.opencanard_user_roles r WHERE r.username = u.username AND r.role_name = 'admin') "
			+ "ORDER BY u.username";

	/**
	 * Makes sure the login has a registered database: nothing happens if it has one; otherwise the existing
	 * {@code <login>.duckdb} is registered, or a new empty file is created and registered. Used for a user that stops
	 * being an admin. Failures are logged, not thrown: the user then cannot connect until the next startup check.
	 */
	public synchronized void ensureDatabase(String login) {
		try (Connection meta = ((DuckDBConnection) metadatadb).duplicate()) {
			if (registry.find(meta, login) == null) {
				ensureDatabase(meta, login);
			}
		} catch (Exception e) {
			logger.error("[user-db] could not check the database of '{}'", login, e);
		}
	}

	private void ensureDatabase(Connection meta, String login) {
		if (!UsernameValidator.isValid(login)) {
			logger.error("[user-db] user '{}' has no database and its name is not a valid file name", login);
			return;
		}
		try {
			if (Files.exists(databaseFile(login))) {
				logger.info("[user-db] user '{}' has no registry row, registering the existing file", login);
			} else {
				logger.info("[user-db] user '{}' has no database, creating it", login);
				create(login);
			}
			registry.insert(meta, login, login, fileName(login));
			logger.info("[user-db] registered '{}' -> {}", login, fileName(login));
		} catch (Exception e) {
			logger.error("[user-db] could not set up the database of '{}'", login, e);
		}
	}

	private DuckDBConnection open(String username, Path file, boolean readOnly) throws SQLException {
		DuckDBConnection conn = (DuckDBConnection) DriverManager
				.getConnection(readOnly ? url(file) + ";access_mode=READ_ONLY" : url(file));
		if (readOnly) {
			// never hand out a writable connection to a read-only user: check what DuckDB actually opened
			boolean confirmed;
			try (Statement stmt = conn.createStatement();
					java.sql.ResultSet rs = stmt.executeQuery(
							"SELECT readonly FROM duckdb_databases() WHERE database_name = current_database()")) {
				confirmed = rs.next() && rs.getBoolean(1);
			} catch (SQLException e) {
				conn.close();
				throw e;
			}
			if (!confirmed) {
				conn.close();
				throw new SQLException("DuckDB did not open " + file + " in read-only mode");
			}
		}
		try (Statement stmt = conn.createStatement()) {
			// Not needed for Arrow streaming (the JDBC driver exports Arrow itself) and not published for DuckDB 2.0:
			// loaded when available, a failure is only logged
			stmt.execute("INSTALL arrow;");
			stmt.execute("LOAD arrow;");
		} catch (SQLException e) {
			logger.warn("[user-db] DuckDB arrow extension not available for '{}' ({}); not needed", username,
					e.getMessage());
		}
		logger.info("[user-db] opened the database of '{}' ({}): {}", username, readOnly ? "READ_ONLY" : "READ_WRITE",
				file);
		return conn;
	}

	@Override
	public synchronized void close() {
		for (Map.Entry<String, DuckDBConnection> entry : open.entrySet()) {
			try {
				entry.getValue().close();
			} catch (SQLException e) {
				logger.error("[user-db] failed to close the database of '{}'", entry.getKey(), e);
			}
		}
		open.clear();
		openReadOnly.clear();
		logger.info("[user-db] all user databases closed");
	}

	private static String url(Path file) {
		return "jdbc:duckdb:" + file.toAbsolutePath().toString().replace("\\", "/");
	}
}
