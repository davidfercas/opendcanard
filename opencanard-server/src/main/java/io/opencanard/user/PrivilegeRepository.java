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
 * The privilege tables of the metadata database:
 * <pre>
 * opencanard_user_privileges  username VARCHAR, privilege VARCHAR, PRIMARY KEY (username, privilege)
 * opencanard_role_privileges  role_name VARCHAR, privilege VARCHAR, PRIMARY KEY (role_name, privilege)
 * </pre>
 * The names come from {@link Privileges}. As with the roles there are no foreign keys: {@link PrivilegeService},
 * {@link RoleService} and {@link UserService} remove or move the rows when a user or role is deleted or renamed.
 * Every method takes the connection to use.
 */
public class PrivilegeRepository {

	public static final String CREATE_USER_PRIVILEGES_TABLE = "CREATE TABLE opencanard_user_privileges(" //
			+ "username VARCHAR NOT NULL, " //
			+ "privilege VARCHAR NOT NULL, " //
			+ "PRIMARY KEY (username, privilege))";

	public static final String CREATE_ROLE_PRIVILEGES_TABLE = "CREATE TABLE opencanard_role_privileges(" //
			+ "role_name VARCHAR NOT NULL, " //
			+ "privilege VARCHAR NOT NULL, " //
			+ "PRIMARY KEY (role_name, privilege))";

	// ---------- users ----------

	/** The privileges granted to the user directly, sorted. */
	public List<String> ofUser(Connection db, String username) throws SQLException {
		return list(db, "SELECT privilege FROM main.opencanard_user_privileges WHERE username = ? ORDER BY privilege",
				username);
	}

	/** The privileges granted directly, for every user that has at least one. */
	public Map<String, List<String>> byUser(Connection db) throws SQLException {
		return map(db, "SELECT username, privilege FROM main.opencanard_user_privileges ORDER BY username, privilege");
	}

	/** True if the row was added (false: the user already had it). */
	public boolean grantToUser(Connection db, String username, String privilege) throws SQLException {
		return insert(db, "opencanard_user_privileges", "username", username, privilege);
	}

	/** True if the row was removed. */
	public boolean revokeFromUser(Connection db, String username, String privilege) throws SQLException {
		return delete(db, "DELETE FROM main.opencanard_user_privileges WHERE username = ? AND privilege = ?", username,
				privilege) > 0;
	}

	public void deleteByUser(Connection db, String username) throws SQLException {
		delete(db, "DELETE FROM main.opencanard_user_privileges WHERE username = ?", username, null);
	}

	// ---------- roles ----------

	/** The privileges granted to the role, sorted. */
	public List<String> ofRole(Connection db, String role) throws SQLException {
		return list(db, "SELECT privilege FROM main.opencanard_role_privileges WHERE role_name = ? ORDER BY privilege",
				role);
	}

	/** The privileges of every role that has at least one. */
	public Map<String, List<String>> byRole(Connection db) throws SQLException {
		return map(db, "SELECT role_name, privilege FROM main.opencanard_role_privileges ORDER BY role_name, privilege");
	}

	public boolean grantToRole(Connection db, String role, String privilege) throws SQLException {
		return insert(db, "opencanard_role_privileges", "role_name", role, privilege);
	}

	public boolean revokeFromRole(Connection db, String role, String privilege) throws SQLException {
		return delete(db, "DELETE FROM main.opencanard_role_privileges WHERE role_name = ? AND privilege = ?", role,
				privilege) > 0;
	}

	public void deleteByRole(Connection db, String role) throws SQLException {
		delete(db, "DELETE FROM main.opencanard_role_privileges WHERE role_name = ?", role, null);
	}

	/** Moves the privileges of a role to its new name (part of a rename, run inside its transaction). */
	public void renameRole(Connection db, String from, String to) throws SQLException {
		try (PreparedStatement ps = db
				.prepareStatement("UPDATE main.opencanard_role_privileges SET role_name = ? WHERE role_name = ?")) {
			ps.setString(1, to);
			ps.setString(2, from);
			ps.executeUpdate();
		}
	}

	// ---------- effective ----------

	/** True if the user has the privilege directly or through one of its roles. */
	public boolean has(Connection db, String username, String privilege) throws SQLException {
		try (PreparedStatement ps = db.prepareStatement("SELECT EXISTS (SELECT 1 FROM main.opencanard_user_privileges "
				+ "WHERE username = ? AND privilege = ?) OR EXISTS (SELECT 1 FROM main.opencanard_role_privileges rp "
				+ "JOIN main.opencanard_user_roles ur ON ur.role_name = rp.role_name "
				+ "WHERE ur.username = ? AND rp.privilege = ?)")) {
			ps.setString(1, username);
			ps.setString(2, privilege);
			ps.setString(3, username);
			ps.setString(4, privilege);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() && rs.getBoolean(1);
			}
		}
	}

	/**
	 * Every privilege of the user, direct or through a role, sorted.
	 */
	public List<String> effective(Connection db, String username) throws SQLException {
		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = db.prepareStatement("SELECT privilege FROM main.opencanard_user_privileges "
				+ "WHERE username = ? UNION SELECT rp.privilege FROM main.opencanard_role_privileges rp "
				+ "JOIN main.opencanard_user_roles ur ON ur.role_name = rp.role_name WHERE ur.username = ? "
				+ "ORDER BY 1")) {
			ps.setString(1, username);
			ps.setString(2, username);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(rs.getString(1));
				}
			}
		}
		return out;
	}

	// ---------- helpers ----------

	private static boolean insert(Connection db, String table, String keyColumn, String key, String privilege)
			throws SQLException {
		try (PreparedStatement ps = db.prepareStatement("INSERT INTO main." + table + "(" + keyColumn + ", privilege) "
				+ "SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM main." + table + " WHERE " + keyColumn
				+ " = ? AND privilege = ?)")) {
			ps.setString(1, key);
			ps.setString(2, privilege);
			ps.setString(3, key);
			ps.setString(4, privilege);
			return ps.executeUpdate() > 0;
		}
	}

	private static int delete(Connection db, String sql, String key, String privilege) throws SQLException {
		try (PreparedStatement ps = db.prepareStatement(sql)) {
			ps.setString(1, key);
			if (privilege != null) {
				ps.setString(2, privilege);
			}
			return ps.executeUpdate();
		}
	}

	private static List<String> list(Connection db, String sql, String key) throws SQLException {
		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = db.prepareStatement(sql)) {
			ps.setString(1, key);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(rs.getString(1));
				}
			}
		}
		return out;
	}

	private static Map<String, List<String>> map(Connection db, String sql) throws SQLException {
		Map<String, List<String>> out = new LinkedHashMap<>();
		try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
			}
		}
		return out;
	}
}
