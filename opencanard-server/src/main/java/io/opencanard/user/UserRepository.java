package io.opencanard.user;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * The table {@code opencanard_users} of the metadata database (username, password hash, creation date). The roles
 * of a user live in {@code opencanard_user_roles}, see {@link RoleRepository}.
 */
public class UserRepository {

	public static final String CREATE_TABLE = "CREATE TABLE opencanard_users(" //
			+ "username VARCHAR PRIMARY KEY, " //
			+ "password_hash VARCHAR NOT NULL, " //
			+ "created_at TIMESTAMP NOT NULL DEFAULT now())";

	private final RoleRepository roles = new RoleRepository();

	/** The user with its roles, or {@code null}. */
	public User getUser(Connection metadatadb, String username) throws Exception {
		String sql = "SELECT username, password_hash FROM main.opencanard_users WHERE username = ?;";
		String user;
		String passwordHash;
		try (PreparedStatement stmt = metadatadb.prepareStatement(sql)) {
			stmt.setString(1, username);
			try (ResultSet rs = stmt.executeQuery()) {
				if (!rs.next()) {
					return null;
				}
				user = rs.getString("username");
				passwordHash = rs.getString("password_hash");
			}
		}
		return new User(user, passwordHash, roles.rolesOf(metadatadb, user));
	}

	/** Inserts a user (without roles); created_at is always set to now(). */
	public void insert(Connection metadatadb, String username, String passwordHash) throws Exception {
		String sql = "INSERT INTO main.opencanard_users(username, password_hash, created_at) VALUES (?, ?, now());";
		try (PreparedStatement stmt = metadatadb.prepareStatement(sql)) {
			stmt.setString(1, username);
			stmt.setString(2, passwordHash);
			stmt.executeUpdate();
		}
	}

	/** Removes a user and its role assignments. */
	public void delete(Connection metadatadb, String username) throws Exception {
		roles.deleteAssignments(metadatadb, username);
		try (PreparedStatement stmt = metadatadb
				.prepareStatement("DELETE FROM main.opencanard_users WHERE username = ?;")) {
			stmt.setString(1, username);
			stmt.executeUpdate();
		}
	}
}
