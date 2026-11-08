package io.opencanard.user;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBConnection;

/**
 * Creates, changes and deletes roles ({@code opencanard_roles}).
 * <ul>
 * <li>The built-in roles {@code admin} and {@code user} always exist: they cannot be deleted or renamed, only their
 * description changes. {@code admin} is the role that gives access to the administration and the metadata
 * database; {@code user} is the default role of a new user.</li>
 * <li>Renaming a role moves its assignments to the new name; deleting it removes it from every user.</li>
 * </ul>
 * Changes run under the lock of {@link UserService}, so they never interleave with a user being created, changed
 * or deleted, and each one runs in its own transaction on a duplicate of the metadata connection.
 */
public class RoleService {

	private static final Logger logger = LogManager.getLogger(RoleService.class);

	public static final String ADMIN = "admin";
	public static final String USER = "user";
	public static final List<String> BUILT_IN = List.of(ADMIN, USER);

	public static final String RULES = "Role name must be 1-64 characters: letters, digits, '_', '-' or '.', "
			+ "starting with a letter or digit";
	public static final int MAX_DESCRIPTION = 256;
	private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

	private final Connection metadatadb;
	private final Object lock;
	private final RoleRepository repository = new RoleRepository();

	/** @param lock the {@link UserService}, whose monitor serializes every change to users and roles */
	public RoleService(Connection metadatadb, Object lock) {
		this.metadatadb = metadatadb;
		this.lock = lock;
	}

	public static boolean isBuiltIn(String name) {
		return BUILT_IN.contains(name);
	}

	public static void validateName(String name) {
		if (name == null || !VALID.matcher(name).matches()) {
			throw new IllegalArgumentException(RULES);
		}
	}

	private static String cleanDescription(String description) {
		String d = description == null ? "" : description.trim();
		if (d.length() > MAX_DESCRIPTION) {
			throw new IllegalArgumentException("Description must be at most " + MAX_DESCRIPTION + " characters");
		}
		return d;
	}

	public List<RoleRepository.Role> list() throws SQLException {
		try (Connection c = duplicate()) {
			return repository.list(c);
		}
	}

	public RoleRepository.Role create(String name, String description) throws Exception {
		validateName(name);
		String d = cleanDescription(description);
		synchronized (lock) {
			try (Connection c = duplicate()) {
				if (repository.find(c, name) != null) {
					throw new RoleAlreadyExistsException("Role '" + name + "' already exists");
				}
				repository.insert(c, name, d);
				logger.info("[roles] role '{}' created", name);
				return repository.find(c, name);
			}
		}
	}

	/**
	 * Changes the description and, for roles that are not built in, the name.
	 *
	 * @param newName {@code null} or equal to {@code name} keeps the name
	 */
	public RoleRepository.Role update(String name, String newName, String description) throws Exception {
		String target = newName == null || newName.isEmpty() ? name : newName;
		validateName(target);
		String d = cleanDescription(description);
		synchronized (lock) {
			try (Connection c = duplicate()) {
				if (repository.find(c, name) == null) {
					throw new NotFoundException("Role '" + name + "' does not exist");
				}
				if (target.equals(name)) {
					repository.updateDescription(c, name, d);
					logger.info("[roles] description of '{}' changed", name);
					return repository.find(c, name);
				}
				if (isBuiltIn(name)) {
					throw new NotAllowedException("The built-in role '" + name + "' cannot be renamed");
				}
				if (repository.find(c, target) != null) {
					throw new RoleAlreadyExistsException("Role '" + target + "' already exists");
				}
				c.setAutoCommit(false);
				try {
					repository.insert(c, target, d);
					repository.renameAssignments(c, name, target);
					repository.delete(c, name);
					c.commit();
				} catch (SQLException e) {
					c.rollback();
					throw e;
				} finally {
					c.setAutoCommit(true);
				}
				logger.info("[roles] role '{}' renamed to '{}'", name, target);
				return repository.find(c, target);
			}
		}
	}

	/** Deletes the role and removes it from every user that had it. */
	public void delete(String name) throws Exception {
		if (isBuiltIn(name)) {
			throw new NotAllowedException("The built-in role '" + name + "' cannot be deleted");
		}
		synchronized (lock) {
			try (Connection c = duplicate()) {
				RoleRepository.Role role = repository.find(c, name);
				if (role == null) {
					throw new NotFoundException("Role '" + name + "' does not exist");
				}
				c.setAutoCommit(false);
				try {
					repository.delete(c, name);
					c.commit();
				} catch (SQLException e) {
					c.rollback();
					throw e;
				} finally {
					c.setAutoCommit(true);
				}
				logger.info("[roles] role '{}' deleted (it was assigned to {} user(s))", name, role.getUsers());
			}
		}
	}

	private Connection duplicate() throws SQLException {
		return ((DuckDBConnection) metadatadb).duplicate();
	}
}
