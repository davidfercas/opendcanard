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
 * Manages the personal DuckDB files of the users: {@code <dir>/<username>.duckdb}.
 * <ul>
 * <li>{@link #create} builds a new, empty database file for a user;</li>
 * <li>{@link #connect} opens (once per user, lazily) the user's file and hands out a new connection to
 * it for every call, so concurrent requests of a user never share a JDBC connection. The caller closes
 * the connection it gets; that does not close the database.</li>
 * </ul>
 * A database file is never created by {@link #connect}: DuckDB creates a file just by connecting to a
 * path, so existence is checked first.
 */
public class UserDatabases implements AutoCloseable {

	private static final Logger logger = LogManager.getLogger(UserDatabases.class);

	private final Path dir;
	/** One open "base" connection per user; it keeps the user's database open between requests. */
	private final Map<String, DuckDBConnection> open = new HashMap<>();

	public UserDatabases(Path dir) throws IOException {
		this.dir = dir.toAbsolutePath().normalize();
		Files.createDirectories(this.dir);
		logger.info("[user-db] user databases are stored in {}", this.dir);
	}

	public Path databaseFile(String username) {
		return UsernameValidator.databaseFile(dir, username);
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
		DuckDBConnection base = open.remove(username);
		if (base != null) {
			base.close();
		}
		boolean removed = Files.deleteIfExists(file);
		Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".wal"));
		logger.info("[user-db] database of '{}' removed (file existed: {})", username, removed);
	}

	/**
	 * A new connection to the user's own database, or {@code null} if the user has none (the name is not a
	 * valid file name, or no file exists). The caller must close it.
	 */
	public Connection connect(String username) throws SQLException {
		if (!UsernameValidator.isValid(username)) {
			return null;
		}
		DuckDBConnection base;
		synchronized (this) {
			base = open.get(username);
			if (base == null) {
				Path file = databaseFile(username);
				if (Files.notExists(file)) {
					return null;
				}
				base = open(username, file);
				open.put(username, base);
			}
		}
		return base.duplicate();
	}

	private DuckDBConnection open(String username, Path file) throws SQLException {
		DuckDBConnection conn = (DuckDBConnection) DriverManager.getConnection(url(file));
		try (Statement stmt = conn.createStatement()) {
			// Needed for the Arrow streaming of query results, once per database
			stmt.execute("INSTALL arrow;");
			stmt.execute("LOAD arrow;");
		} catch (SQLException e) {
			conn.close();
			throw e;
		}
		logger.info("[user-db] opened the database of '{}': {}", username, file);
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
		logger.info("[user-db] all user databases closed");
	}

	private static String url(Path file) {
		return "jdbc:duckdb:" + file.toAbsolutePath().toString().replace("\\", "/");
	}
}
