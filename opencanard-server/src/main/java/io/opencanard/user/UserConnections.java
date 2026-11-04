package io.opencanard.user;

import java.sql.Connection;
import java.sql.SQLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBConnection;

/**
 * Decides which database a login works on, for every entry point that runs SQL on behalf of a user
 * (Arrow Flight and the web console):
 * <ul>
 * <li>users with the role {@code admin} get the <b>metadata</b> database;</li>
 * <li>every other user gets the DuckDB file registered for the login in {@code opencanard_databases};</li>
 * <li>a login without a registered database is refused with {@link NoDatabaseException}: there is no
 * shared fallback.</li>
 * </ul>
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
	private final UserRepository userRepository = new UserRepository();

	public UserConnections(Connection metadatadb, UserDatabases userDatabases) {
		this.metadatadb = metadatadb;
		this.userDatabases = userDatabases;
	}

	/** A new connection to the database of the login. The caller must close it. */
	public Connection open(String login) throws SQLException {
		if (isAdmin(login)) {
			logger.debug("'{}' is an admin and uses the metadata database", login);
			return ((DuckDBConnection) metadatadb).duplicate();
		}
		Connection personal = userDatabases.connect(login);
		if (personal == null) {
			throw new NoDatabaseException("No database is registered for '" + login + "' in opencanard_databases");
		}
		logger.debug("'{}' uses its registered database", login);
		return personal;
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
