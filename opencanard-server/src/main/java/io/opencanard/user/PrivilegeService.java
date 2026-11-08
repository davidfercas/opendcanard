package io.opencanard.user;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBConnection;

/**
 * Grants and revokes privileges ({@link Privileges}) on users and roles. A user has a privilege when it was granted
 * to the user or to one of its roles. Changes run under the lock of {@link UserService}, like role changes, and take
 * effect on the next connection the user opens (see {@link UserConnections}).
 */
public class PrivilegeService {

	private static final Logger logger = LogManager.getLogger(PrivilegeService.class);

	private final Connection metadatadb;
	private final Object lock;
	private final PrivilegeRepository repository = new PrivilegeRepository();
	private final UserRepository users = new UserRepository();
	private final RoleRepository roles = new RoleRepository();

	/** @param lock the {@link UserService}, whose monitor serializes every change to users, roles and privileges */
	public PrivilegeService(Connection metadatadb, Object lock) {
		this.metadatadb = metadatadb;
		this.lock = lock;
	}

	/** The canonical name of a privilege of the catalog. */
	public static String validate(String privilege) {
		Privileges.Privilege p = Privileges.find(privilege);
		if (p == null) {
			throw new IllegalArgumentException("Unknown privilege '" + privilege + "'");
		}
		return p.getName();
	}

	/** Grants ({@code granted = true}) or revokes a privilege on a user; returns the user's direct privileges. */
	public List<String> setForUser(String username, String privilege, boolean granted, String requestedBy)
			throws Exception {
		String name = validate(privilege);
		synchronized (lock) {
			try (Connection c = duplicate()) {
				if (users.getUser(c, username) == null) {
					throw new NotFoundException("User '" + username + "' does not exist");
				}
				boolean changed = granted ? repository.grantToUser(c, username, name)
						: repository.revokeFromUser(c, username, name);
				if (changed) {
					logger.info("[privileges] '{}' {} {} {} user '{}'", requestedBy, granted ? "granted" : "revoked",
							name, granted ? "to" : "from", username);
				}
				return repository.ofUser(c, username);
			}
		}
	}

	/** Grants or revokes a privilege on a role; returns the role's privileges. */
	public List<String> setForRole(String role, String privilege, boolean granted, String requestedBy)
			throws Exception {
		String name = validate(privilege);
		synchronized (lock) {
			try (Connection c = duplicate()) {
				if (roles.find(c, role) == null) {
					throw new NotFoundException("Role '" + role + "' does not exist");
				}
				boolean changed = granted ? repository.grantToRole(c, role, name)
						: repository.revokeFromRole(c, role, name);
				if (changed) {
					logger.info("[privileges] '{}' {} {} {} role '{}'", requestedBy, granted ? "granted" : "revoked",
							name, granted ? "to" : "from", role);
				}
				return repository.ofRole(c, role);
			}
		}
	}

	public List<String> ofUser(String username) throws SQLException {
		try (Connection c = duplicate()) {
			return repository.ofUser(c, username);
		}
	}

	public List<String> effective(String username) throws SQLException {
		try (Connection c = duplicate()) {
			return repository.effective(c, username);
		}
	}

	private Connection duplicate() throws SQLException {
		return ((DuckDBConnection) metadatadb).duplicate();
	}
}
