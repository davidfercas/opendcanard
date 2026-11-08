package io.opencanard.user;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The roles tables of the metadata database:
 * <pre>
 * opencanard_roles       name VARCHAR PRIMARY KEY, description VARCHAR
 * opencanard_user_roles  username VARCHAR, role_name VARCHAR, PRIMARY KEY (username, role_name)
 * </pre>
 * A user has any number of roles. There are no foreign keys (DuckDB cannot update a key that is referenced), so
 * {@link RoleService} and {@link UserService} keep the two tables consistent. Every method takes the connection to
 * use, so callers decide which (duplicate) connection and transaction runs it.
 */
public class RoleRepository {

	public static final String CREATE_ROLES_TABLE = "CREATE TABLE opencanard_roles(" //
			+ "name VARCHAR PRIMARY KEY, " //
			+ "description VARCHAR NOT NULL DEFAULT '')";

	public static final String CREATE_USER_ROLES_TABLE = "CREATE TABLE opencanard_user_roles(" //
			+ "username VARCHAR NOT NULL, " //
			+ "role_name VARCHAR NOT NULL, " //
			+ "PRIMARY KEY (username, role_name))";

	/** One row of {@code opencanard_roles}, with the number of users that have it. */
	public static final class Role {
		private final String name;
		private final String description;
		private final int users;

		public Role(String name, String description, int users) {
			this.name = name;
			this.description = description;
			this.users = users;
		}

		public String getName() {
			return name;
		}

		public String getDescription() {
			return description;
		}

		public int getUsers() {
			return users;
		}
	}

	private static final String SELECT = "SELECT r.name, r.description, "
			+ "(SELECT COUNT(*) FROM main.opencanard_user_roles ur WHERE ur.role_name = r.name) AS users "
			+ "FROM main.opencanard_roles r";

	public List<Role> list(Connection db) throws SQLException {
		List<Role> roles = new ArrayList<>();
		try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(SELECT + " ORDER BY r.name")) {
			while (rs.next()) {
				roles.add(read(rs));
			}
		}
		return roles;
	}

	/** The role, or {@code null}. */
	public Role find(Connection db, String name) throws SQLException {
		try (PreparedStatement ps = db.prepareStatement(SELECT + " WHERE r.name = ?")) {
			ps.setString(1, name);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? read(rs) : null;
			}
		}
	}

	public void insert(Connection db, String name, String description) throws SQLException {
		try (PreparedStatement ps = db
				.prepareStatement("INSERT INTO main.opencanard_roles(name, description) VALUES (?, ?)")) {
			ps.setString(1, name);
			ps.setString(2, description == null ? "" : description);
			ps.executeUpdate();
		}
	}

	/** Inserts the role only if there is none with that name (used at startup for the built-in roles). */
	public void insertIfMissing(Connection db, String name, String description) throws SQLException {
		try (PreparedStatement ps = db.prepareStatement("INSERT INTO main.opencanard_roles(name, description) "
				+ "SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM main.opencanard_roles WHERE name = ?)")) {
			ps.setString(1, name);
			ps.setString(2, description);
			ps.setString(3, name);
			ps.executeUpdate();
		}
	}

	public int updateDescription(Connection db, String name, String description) throws SQLException {
		try (PreparedStatement ps = db
				.prepareStatement("UPDATE main.opencanard_roles SET description = ? WHERE name = ?")) {
			ps.setString(1, description == null ? "" : description);
			ps.setString(2, name);
			return ps.executeUpdate();
		}
	}

	/** Moves every assignment of a role to another name (part of a rename, run inside its transaction). */
	public void renameAssignments(Connection db, String from, String to) throws SQLException {
		try (PreparedStatement ps = db
				.prepareStatement("UPDATE main.opencanard_user_roles SET role_name = ? WHERE role_name = ?")) {
			ps.setString(1, to);
			ps.setString(2, from);
			ps.executeUpdate();
		}
	}

	/** Removes the role and all its assignments. */
	public int delete(Connection db, String name) throws SQLException {
		try (PreparedStatement ps = db.prepareStatement("DELETE FROM main.opencanard_user_roles WHERE role_name = ?")) {
			ps.setString(1, name);
			ps.executeUpdate();
		}
		try (PreparedStatement ps = db.prepareStatement("DELETE FROM main.opencanard_roles WHERE name = ?")) {
			ps.setString(1, name);
			return ps.executeUpdate();
		}
	}

	/** The roles of one user, sorted by name. */
	public List<String> rolesOf(Connection db, String username) throws SQLException {
		List<String> roles = new ArrayList<>();
		try (PreparedStatement ps = db.prepareStatement(
				"SELECT role_name FROM main.opencanard_user_roles WHERE username = ? ORDER BY role_name")) {
			ps.setString(1, username);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					roles.add(rs.getString(1));
				}
			}
		}
		return roles;
	}

	/** The roles of every user that has at least one. */
	public Map<String, List<String>> rolesByUser(Connection db) throws SQLException {
		Map<String, List<String>> out = new LinkedHashMap<>();
		try (Statement st = db.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT username, role_name FROM main.opencanard_user_roles ORDER BY username, role_name")) {
			while (rs.next()) {
				out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
			}
		}
		return out;
	}

	/** Replaces the roles of a user (run inside a transaction). */
	public void setRoles(Connection db, String username, List<String> roles) throws SQLException {
		deleteAssignments(db, username);
		try (PreparedStatement ps = db
				.prepareStatement("INSERT INTO main.opencanard_user_roles(username, role_name) VALUES (?, ?)")) {
			for (String role : roles) {
				ps.setString(1, username);
				ps.setString(2, role);
				ps.executeUpdate();
			}
		}
	}

	/** Removes every role of a user. */
	public void deleteAssignments(Connection db, String username) throws SQLException {
		try (PreparedStatement ps = db.prepareStatement("DELETE FROM main.opencanard_user_roles WHERE username = ?")) {
			ps.setString(1, username);
			ps.executeUpdate();
		}
	}

	private static Role read(ResultSet rs) throws SQLException {
		String description = rs.getString("description");
		return new Role(rs.getString("name"), description == null ? "" : description, rs.getInt("users"));
	}
}
