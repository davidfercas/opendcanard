package io.opencanard.user;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBConnection;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import io.opencanard.storage.StorageRepository;

/**
 * Creates, changes and deletes users.
 * <ul>
 * <li>A user is a row in {@code opencanard_users} (BCrypt-hashed password), one or more rows in
 * {@code opencanard_user_roles} and its own DuckDB file named after the login, registered in
 * {@code opencanard_databases}. If a step of the creation fails, what was already created is removed again.</li>
 * <li>The built-in admin user (the {@code admin.user} of the configuration) can never be deleted and always keeps the
 * {@code admin} role. Nobody can delete their own account or take the {@code admin} role away from themselves, so
 * there is always an admin left.</li>
 * </ul>
 * Every change is serialized on this object; {@link RoleService} uses the same lock.
 */
public class UserService {

	private static final Logger logger = LogManager.getLogger(UserService.class);

	public static final String DEFAULT_ROLE = RoleService.USER;
	/** BCrypt only uses the first 72 bytes of a password. */
	private static final int MAX_PASSWORD_BYTES = 72;

	private final Connection metadatadb;
	private final UserDatabases userDatabases;
	private final String protectedAdmin;
	private final UserRepository userRepository = new UserRepository();
	private final RoleRepository roleRepository = new RoleRepository();
	private final DatabaseRegistry databaseRegistry = new DatabaseRegistry();

	/** @param protectedAdmin the built-in admin user ({@code admin.user}), which cannot be deleted */
	public UserService(Connection metadatadb, UserDatabases userDatabases, String protectedAdmin) {
		this.metadatadb = metadatadb;
		this.userDatabases = userDatabases;
		this.protectedAdmin = protectedAdmin;
	}

	/** The built-in admin user, which cannot be deleted nor lose the admin role. */
	public String getProtectedAdmin() {
		return protectedAdmin;
	}

	public boolean isProtected(String username) {
		return protectedAdmin != null && protectedAdmin.equals(username);
	}

	/**
	 * @param roles names of existing roles; {@code null} or empty means {@code user}
	 * @throws IllegalArgumentException   invalid username, password or role
	 * @throws UserAlreadyExistsException the user, or a database file with that name, already exists
	 */
	public synchronized User createUser(String username, String password, List<String> roles) throws Exception {
		UsernameValidator.validate(username);

		if (password == null || password.isEmpty()) {
			throw new IllegalArgumentException("Password must not be empty");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new IllegalArgumentException("Password must be at most " + MAX_PASSWORD_BYTES + " bytes");
		}

		try (Connection c = duplicate()) {
			List<String> clean = cleanRoles(c, roles == null || roles.isEmpty() ? List.of(DEFAULT_ROLE) : roles);

			if (userRepository.getUser(c, username) != null) {
				throw new UserAlreadyExistsException("User '" + username + "' already exists");
			}
			// Also covers file systems that ignore case (Admin / admin would be the same file) and files
			// left over from an interrupted creation: those are never overwritten.
			if (userDatabases.exists(username)) {
				throw new UserAlreadyExistsException(
						"A database file for '" + username + "' already exists; remove it first if it is not in use");
			}

			String passwordHash = new BCryptPasswordEncoder().encode(password);
			logger.info("[create-user] creating user '{}' with roles {}", username, clean);

			userDatabases.create(username);
			c.setAutoCommit(false);
			try {
				databaseRegistry.insert(c, username, username, UserDatabases.fileName(username));
				userRepository.insert(c, username, passwordHash);
				roleRepository.setRoles(c, username, clean);
				c.commit();
			} catch (Exception e) {
				logger.error("[create-user] could not create '{}', removing its database file", username, e);
				try {
					c.rollback();
				} catch (SQLException ignored) {
					// the connection is closed below anyway
				}
				userDatabases.delete(username);
				throw e;
			} finally {
				c.setAutoCommit(true);
			}

			logger.info("[create-user] user '{}' created (database: {})", username,
					userDatabases.databaseFile(username));
			return new User(username, passwordHash, clean);
		}
	}

	/**
	 * Replaces the roles of a user. A user who stops being an admin gets its personal database (created if it does
	 * not exist yet), since only admins work on the metadata database.
	 *
	 * @param requestedBy the admin asking for it
	 * @return the user with its new roles
	 */
	public synchronized User setRoles(String username, List<String> roles, String requestedBy) throws Exception {
		if (roles == null || roles.isEmpty()) {
			throw new IllegalArgumentException("A user needs at least one role");
		}
		User user;
		List<String> clean;
		try (Connection c = duplicate()) {
			user = userRepository.getUser(c, username);
			if (user == null) {
				throw new NotFoundException("User '" + username + "' does not exist");
			}
			clean = cleanRoles(c, roles);
			boolean keepsAdmin = clean.contains(RoleService.ADMIN);
			if (!keepsAdmin && isProtected(username)) {
				throw new NotAllowedException("The admin user '" + username + "' must keep the role 'admin'");
			}
			if (!keepsAdmin && username.equals(requestedBy) && user.getRoles().contains(RoleService.ADMIN)) {
				throw new NotAllowedException("You cannot remove the role 'admin' from your own account");
			}
			c.setAutoCommit(false);
			try {
				roleRepository.setRoles(c, username, clean);
				c.commit();
			} catch (SQLException e) {
				c.rollback();
				throw e;
			} finally {
				c.setAutoCommit(true);
			}
		}
		logger.info("[user-roles] '{}' set the roles of '{}' to {} (were {})", requestedBy, username, clean,
				user.getRoles());
		if (!clean.contains(RoleService.ADMIN)) {
			userDatabases.ensureDatabase(username);
		}
		return new User(username, user.getPasswordHash(), clean);
	}

	/**
	 * Deletes a user and everything that belongs to it:
	 * <ol>
	 * <li>every DuckDB file registered for the login in {@code opencanard_databases}, plus the default
	 * {@code <login>.duckdb} if it is there, is closed and removed from the data folder (with its {@code .wal});</li>
	 * <li>the rows of the login in {@code opencanard_databases}, {@code opencanard_storages} and
	 * {@code opencanard_user_roles};</li>
	 * <li>the row in {@code opencanard_users}.</li>
	 * </ol>
	 * The files go first: if one cannot be removed (still open by a running query on Windows, for example) the
	 * call fails and the user is kept, so it can be retried.
	 *
	 * @param requestedBy the admin asking for it; nobody can delete their own account
	 * @return the database files that were removed
	 * @throws NotAllowedException the built-in admin user, or the caller's own account
	 * @throws NotFoundException   the user does not exist
	 */
	public synchronized List<String> deleteUser(String username, String requestedBy) throws Exception {
		if (username == null || username.isEmpty()) {
			throw new IllegalArgumentException("Username must not be empty");
		}
		if (isProtected(username)) {
			throw new NotAllowedException("The admin user '" + username + "' cannot be deleted");
		}
		if (username.equals(requestedBy)) {
			throw new NotAllowedException("You cannot delete your own account");
		}
		try (Connection c = duplicate()) {
			if (userRepository.getUser(c, username) == null) {
				throw new NotFoundException("User '" + username + "' does not exist");
			}

			LinkedHashSet<String> files = new LinkedHashSet<>();
			for (DatabaseRegistry.Entry e : databaseRegistry.listByLogin(c, username)) {
				files.add(e.getDuckdbFile());
			}
			if (UsernameValidator.isValid(username)) {
				files.add(UserDatabases.fileName(username));
			}

			logger.info("[delete-user] '{}' deletes user '{}' and its database files {}", requestedBy, username,
					files);
			List<String> removed = new ArrayList<>();
			for (String file : files) {
				if (userDatabases.deleteFile(file)) {
					removed.add(file);
				}
			}

			int storages;
			c.setAutoCommit(false);
			try {
				databaseRegistry.deleteByLogin(c, username);
				storages = new StorageRepository().deleteByLogin(c, username);
				userRepository.delete(c, username);
				c.commit();
			} catch (Exception e) {
				c.rollback();
				throw e;
			} finally {
				c.setAutoCommit(true);
			}
			logger.info("[delete-user] user '{}' deleted ({} database file(s) and {} storage(s) removed)", username,
					removed.size(), storages);
			return removed;
		}
	}

	/** Removes duplicates and checks that every role exists. */
	private List<String> cleanRoles(Connection c, List<String> roles) throws Exception {
		LinkedHashSet<String> unique = new LinkedHashSet<>();
		for (String role : roles) {
			if (role == null || role.isBlank()) {
				throw new IllegalArgumentException("Role names must not be empty");
			}
			unique.add(role.trim());
		}
		for (String role : unique) {
			if (roleRepository.find(c, role) == null) {
				throw new IllegalArgumentException("Role '" + role + "' does not exist");
			}
		}
		List<String> sorted = new ArrayList<>(unique);
		sorted.sort(null);
		return sorted;
	}

	private Connection duplicate() throws SQLException {
		return ((DuckDBConnection) metadatadb).duplicate();
	}
}
