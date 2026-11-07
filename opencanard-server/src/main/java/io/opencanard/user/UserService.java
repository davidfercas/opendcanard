package io.opencanard.user;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Creates users: a row in {@code opencanard_users} (BCrypt-hashed password) plus the user's own DuckDB
 * file named after the login, registered in {@code opencanard_databases}. Creation is serialized, and if a
 * row cannot be written the new database file and registry row are removed again, so a failed creation leaves nothing behind.
 */
public class UserService {

	private static final Logger logger = LogManager.getLogger(UserService.class);

	public static final String DEFAULT_ROLE = "user";
	public static final List<String> ROLES = List.of("admin", "user");
	/** BCrypt only uses the first 72 bytes of a password. */
	private static final int MAX_PASSWORD_BYTES = 72;

	private final Connection metadatadb;
	private final UserDatabases userDatabases;
	private final UserRepository userRepository = new UserRepository();
	private final DatabaseRegistry databaseRegistry = new DatabaseRegistry();

	public UserService(Connection metadatadb, UserDatabases userDatabases) {
		this.metadatadb = metadatadb;
		this.userDatabases = userDatabases;
	}

	/**
	 * @param role {@code admin} or {@code user}; {@code null} means {@code user}
	 * @throws IllegalArgumentException   invalid username, password or role
	 * @throws UserAlreadyExistsException the user, or a database file with that name, already exists
	 */
	public synchronized User createUser(String username, String password, String role) throws Exception {
		UsernameValidator.validate(username);

		if (password == null || password.isEmpty()) {
			throw new IllegalArgumentException("Password must not be empty");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new IllegalArgumentException("Password must be at most " + MAX_PASSWORD_BYTES + " bytes");
		}

		if (role == null || role.isEmpty()) {
			role = DEFAULT_ROLE;
		}
		if (!ROLES.contains(role)) {
			throw new IllegalArgumentException("Role must be one of " + ROLES);
		}

		if (userRepository.getUser(metadatadb, username) != null) {
			throw new UserAlreadyExistsException("User '" + username + "' already exists");
		}
		// Also covers file systems that ignore case (Admin / admin would be the same file) and files
		// left over from an interrupted creation: those are never overwritten.
		if (userDatabases.exists(username)) {
			throw new UserAlreadyExistsException(
					"A database file for '" + username + "' already exists; remove it first if it is not in use");
		}

		String passwordHash = new BCryptPasswordEncoder().encode(password);
		logger.info("[create-user] creating user '{}' with role '{}'", username, role);

		userDatabases.create(username);
		try {
			databaseRegistry.insert(metadatadb, username, username, UserDatabases.fileName(username));
			try {
				userRepository.insert(metadatadb, username, passwordHash, role);
			} catch (Exception e) {
				databaseRegistry.deleteByLogin(metadatadb, username);
				throw e;
			}
		} catch (Exception e) {
			logger.error("[create-user] could not create '{}', removing its database file and registry row", username,
					e);
			userDatabases.delete(username);
			throw e;
		}

		logger.info("[create-user] user '{}' created (database: {})", username, userDatabases.databaseFile(username));
		return new User(username, passwordHash, List.of(role));
	}

	/** The user cannot be deleted, for example an admin removing their own account. */
	public static class CannotDeleteUserException extends Exception {
		private static final long serialVersionUID = 1L;

		public CannotDeleteUserException(String message) {
			super(message);
		}
	}

	/** The user to delete does not exist. */
	public static class UserNotFoundException extends Exception {
		private static final long serialVersionUID = 1L;

		public UserNotFoundException(String message) {
			super(message);
		}
	}

	/**
	 * Deletes a user and everything that belongs to it:
	 * <ol>
	 * <li>every DuckDB file registered for the login in {@code opencanard_databases}, plus the default
	 * {@code <login>.duckdb} if it is there, is closed and removed from the data folder (with its {@code .wal});</li>
	 * <li>the rows of the login in {@code opencanard_databases} and {@code opencanard_storages};</li>
	 * <li>the row in {@code opencanard_users}.</li>
	 * </ol>
	 * The files go first: if one cannot be removed (still open by a running query on Windows, for example) the
	 * call fails and the user is kept, so it can be retried.
	 *
	 * @param requestedBy the admin asking for it; nobody can delete their own account
	 * @return the database files that were removed
	 */
	public synchronized List<String> deleteUser(String username, String requestedBy) throws Exception {
		if (username == null || username.isEmpty()) {
			throw new IllegalArgumentException("Username must not be empty");
		}
		if (username.equals(requestedBy)) {
			throw new CannotDeleteUserException("You cannot delete your own account");
		}
		if (userRepository.getUser(metadatadb, username) == null) {
			throw new UserNotFoundException("User '" + username + "' does not exist");
		}

		java.util.LinkedHashSet<String> files = new java.util.LinkedHashSet<>();
		for (DatabaseRegistry.Entry e : databaseRegistry.listByLogin(metadatadb, username)) {
			files.add(e.getDuckdbFile());
		}
		if (UsernameValidator.isValid(username)) {
			files.add(UserDatabases.fileName(username));
		}

		logger.info("[delete-user] '{}' deletes user '{}' and its database files {}", requestedBy, username, files);
		List<String> removed = new java.util.ArrayList<>();
		for (String file : files) {
			if (userDatabases.deleteFile(file)) {
				removed.add(file);
			}
		}

		databaseRegistry.deleteByLogin(metadatadb, username);
		int storages = new io.opencanard.storage.StorageRepository().deleteByLogin(metadatadb, username);
		userRepository.delete(metadatadb, username);
		logger.info("[delete-user] user '{}' deleted ({} database file(s) and {} storage(s) removed)", username,
				removed.size(), storages);
		return removed;
	}
}
