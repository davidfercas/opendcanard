package io.opencanard.user;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * The table {@code opencanard_databases} of the metadata database: which DuckDB file belongs to which login.
 * <pre>
 * login          VARCHAR    the user
 * database_name  VARCHAR    logical name of the database (the login, for the default one)
 * duckdb_file    VARCHAR    file name inside data/users/
 * created_at     TIMESTAMP  set with now()
 * </pre>
 * Every method takes the connection to use, so callers decide which (duplicate) connection runs it.
 */
public class DatabaseRegistry {

	public static final String CREATE_TABLE = "CREATE TABLE opencanard_databases(" //
			+ "login VARCHAR NOT NULL, " //
			+ "database_name VARCHAR NOT NULL, " //
			+ "duckdb_file VARCHAR NOT NULL, " //
			+ "created_at TIMESTAMP NOT NULL DEFAULT now(), " //
			+ "PRIMARY KEY (login, database_name))";

	/** One row of the registry. */
	public static final class Entry {
		private final String login;
		private final String databaseName;
		private final String duckdbFile;
		private final Timestamp createdAt;

		public Entry(String login, String databaseName, String duckdbFile, Timestamp createdAt) {
			this.login = login;
			this.databaseName = databaseName;
			this.duckdbFile = duckdbFile;
			this.createdAt = createdAt;
		}

		public String getLogin() {
			return login;
		}

		public String getDatabaseName() {
			return databaseName;
		}

		public String getDuckdbFile() {
			return duckdbFile;
		}

		public Timestamp getCreatedAt() {
			return createdAt;
		}
	}

	/** The database a login connects to, or {@code null}. With several, the oldest one. */
	public Entry find(Connection metadatadb, String login) throws SQLException {
		String sql = "SELECT login, database_name, duckdb_file, created_at FROM main.opencanard_databases "
				+ "WHERE login = ? ORDER BY created_at, database_name LIMIT 1";
		try (PreparedStatement ps = metadatadb.prepareStatement(sql)) {
			ps.setString(1, login);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? read(rs) : null;
			}
		}
	}

	public List<Entry> list(Connection metadatadb) throws SQLException {
		List<Entry> entries = new ArrayList<>();
		try (Statement st = metadatadb.createStatement();
				ResultSet rs = st.executeQuery("SELECT login, database_name, duckdb_file, created_at "
						+ "FROM main.opencanard_databases ORDER BY login, database_name")) {
			while (rs.next()) {
				entries.add(read(rs));
			}
		}
		return entries;
	}

	/** Registers a database; created_at is always set to now(). */
	public void insert(Connection metadatadb, String login, String databaseName, String duckdbFile)
			throws SQLException {
		String sql = "INSERT INTO main.opencanard_databases(login, database_name, duckdb_file, created_at) "
				+ "VALUES (?, ?, ?, now())";
		try (PreparedStatement ps = metadatadb.prepareStatement(sql)) {
			ps.setString(1, login);
			ps.setString(2, databaseName);
			ps.setString(3, duckdbFile);
			ps.executeUpdate();
		}
	}

	/** Removes all the databases registered for the login. */
	public void deleteByLogin(Connection metadatadb, String login) throws SQLException {
		try (PreparedStatement ps = metadatadb
				.prepareStatement("DELETE FROM main.opencanard_databases WHERE login = ?")) {
			ps.setString(1, login);
			ps.executeUpdate();
		}
	}

	private static Entry read(ResultSet rs) throws SQLException {
		return new Entry(rs.getString("login"), rs.getString("database_name"), rs.getString("duckdb_file"),
				rs.getTimestamp("created_at"));
	}
}
