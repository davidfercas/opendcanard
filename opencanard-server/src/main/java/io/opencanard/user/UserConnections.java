package io.opencanard.user;

import java.sql.Connection;
import java.sql.SQLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBConnection;

import io.opencanard.storage.StorageService;

/**
 * Decides which database a login works on, for every entry point that runs SQL on behalf of a user
 * (Arrow Flight and the web console):
 * <ul>
 * <li>users with the role {@code admin} get the <b>metadata</b> database;</li>
 * <li>every other user gets the DuckDB file registered for the login in {@code opencanard_databases};</li>
 * <li>a login without a registered database is refused with {@link NoDatabaseException}: there is no
 * shared fallback.</li>
 * </ul>
 * A non-admin user with the privilege {@link Privileges#READ_ONLY} (granted to the user or to one of its roles) gets
 * its database opened with {@code access_mode=READ_ONLY}. The privilege is read again on every call, so a grant or a
 * revoke applies from the next connection on. Admins always work read-write on the metadata database, which the
 * server itself keeps open read-write; for them the privilege is ignored.
 * Before a connection is returned, the S3 storages of the login are registered in it (see
 * {@link StorageService#apply}), so queries on {@code s3://bucket/...} use the user's own credentials.
 * Each call returns a new connection (a duplicate of the open database), which the caller closes. Closing it
 * never closes the underlying database.
 */
public class UserConnections {

	private static final Logger logger = LogManager.getLogger(UserConnections.class);

	/** The login has no database registered in {@code opencanard_databases}. */
	public static class NoDatabaseException extends SQLException {
		private static final long serialVersionUID = 1L;

		public NoDatabaseException(String message) {
			super(message);
		}
	}

	private final Connection metadatadb;
	private final UserDatabases userDatabases;
	private final StorageService storages;
	private final UserRepository userRepository = new UserRepository();
	private final PrivilegeRepository privileges = new PrivilegeRepository();

	public UserConnections(Connection metadatadb, UserDatabases userDatabases, StorageService storages) {
		this.metadatadb = metadatadb;
		this.userDatabases = userDatabases;
		this.storages = storages;
	}

	/** A new connection to the database of the login. The caller must close it. */
	public Connection open(String login) throws SQLException {
		Connection conn = openWithoutStorages(login);
		try {
			storages.apply(conn, login);
		} catch (Exception e) {
			// the connection is still good for everything that does not touch S3
			logger.warn("Cannot register the S3 storages of '{}': {}", login, e.getMessage());
		}
		return conn;
	}

	/**
	 * Registers the S3 storages of the login in its database now, so they work from the next query on, and reports
	 * why when that is not possible (for example, the DuckDB httpfs extension cannot be installed).
	 */
	public void registerStorages(String login) throws SQLException {
		try (Connection conn = openWithoutStorages(login)) {
			storages.apply(conn, login);
		}
	}

	private Connection openWithoutStorages(String login) throws SQLException {
		if (isAdmin(login)) {
			logger.debug("'{}' is an admin and uses the metadata database", login);
			return ((DuckDBConnection) metadatadb).duplicate();
		}
		boolean readOnly = isReadOnly(login);
		Connection personal = userDatabases.connect(login, readOnly);
		if (personal == null) {
			throw new NoDatabaseException("No database is registered for '" + login + "' in opencanard_databases");
		}
		logger.debug("'{}' uses its registered database{}", login, readOnly ? " (read-only)" : "");
		return personal;
	}

	/**
	 * True when the login has the privilege {@link Privileges#READ_ONLY}, directly or through a role. If the
	 * privileges cannot be read the connection is refused rather than opened writable.
	 */
	public boolean isReadOnly(String login) throws SQLException {
		if (login == null) {
			return false;
		}
		try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
			return privileges.has(c, login, Privileges.READ_ONLY);
		}
	}

	/** True when the login has the role {@code admin} in the metadata database. */
	public boolean isAdmin(String login) {
		if (login == null) {
			return false;
		}
		try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
			User user = userRepository.getUser(c, login);
			return user != null && user.getRoles() != null && user.getRoles().contains("admin");
		} catch (Exception e) {
			logger.error("Cannot read the role of '{}', treating it as a non-admin", login, e);
			return false;
		}
	}
}
