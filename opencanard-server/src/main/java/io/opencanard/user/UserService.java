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
}
