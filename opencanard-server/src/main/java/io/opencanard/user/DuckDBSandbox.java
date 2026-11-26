package io.opencanard.user;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Locks down a DuckDB database before connections to it are handed out to a (non-admin) user, so SQL from the client
 * cannot reach the server's file system or network (#24).
 * <p>
 * Without this, DuckDB lets any query read and write files with the rights of the server process, for example
 * {@code read_blob('metadata/jwt.key')} (the key that signs the login tokens), {@code ATTACH} of the metadata database
 * or of another user's database, or {@code COPY ... TO} any path.
 * <p>
 * {@link #apply} runs these statements once on the first connection of a database, after the server has loaded the
 * extensions it needs ({@code arrow}, {@code httpfs}), and then checks in {@code duckdb_settings()} that they hold:
 * <ol>
 * <li>{@code allow_persistent_secrets = false}: secrets live in memory only (otherwise DuckDB reads
 * {@code ~/.duckdb/stored_secrets}, which the sandbox forbids);</li>
 * <li>{@code autoinstall_known_extensions}, {@code autoload_known_extensions}, {@code allow_community_extensions}
 * off;</li>
 * <li>{@code allowed_directories}: the S3 storages of the user ({@code s3://bucket/folder/}) and the directories in
 * {@code sandbox.allowed.directories};</li>
 * <li>{@code allowed_paths}: the system CA bundle, which httpfs must read to verify HTTPS certificates of S3;</li>
 * <li>{@code enable_external_access = false}: every other file, {@code http(s)://} URL, {@code ATTACH},
 * {@code COPY ... TO}, {@code INSTALL} and {@code LOAD} is refused;</li>
 * <li>{@code lock_configuration = true}: the user cannot change any of the above (nor any other setting).</li>
 * </ol>
 * The user's own database file and its temporary directory stay usable: DuckDB opened them before the lock.
 * These are settings of the database instance, so they apply to every connection duplicated from it and cannot be
 * changed afterwards: when the allowed directories of a user change, the database must be closed and opened again
 * (see {@link UserDatabases#connect}).
 */
public final class DuckDBSandbox {

	private static final Logger logger = LogManager.getLogger(DuckDBSandbox.class);

	/** Where httpfs looks for the CA certificates (the files listed in its own error message). */
	static final List<String> CA_BUNDLES = List.of("/etc/ssl/certs/ca-certificates.crt",
			"/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem", "/etc/pki/tls/certs/ca-bundle.crt",
			"/etc/ssl/ca-bundle.pem", "/etc/ssl/cert.pem");

	/** The settings {@link #apply} checks after running its statements, with the value they must have. */
	private static final Map<String, String> REQUIRED = new TreeMap<>(Map.of( //
			"enable_external_access", "false", //
			"lock_configuration", "true", //
			"allow_persistent_secrets", "false", //
			"autoinstall_known_extensions", "false", //
			"autoload_known_extensions", "false", //
			"allow_community_extensions", "false"));

	private final List<String> configuredDirectories;
	private final List<String> allowedFiles;

	/**
	 * @param configuredDirectories extra directories or URL prefixes every user may read and write (property
	 *                              {@code sandbox.allowed.directories}), for example a shared folder of Parquet
	 *                              files
	 */
	public DuckDBSandbox(Collection<String> configuredDirectories) {
		this.configuredDirectories = normalize(configuredDirectories);
		List<String> files = new ArrayList<>();
		for (String bundle : CA_BUNDLES) {
			if (Files.isRegularFile(Paths.get(bundle))) {
				files.add(bundle);
			}
		}
		this.allowedFiles = Collections.unmodifiableList(files);
		logger.info("[sandbox] user queries can reach only their own database, their S3 storages{}",
				this.configuredDirectories.isEmpty() ? ""
						: " and " + String.join(", ", this.configuredDirectories));
	}

	/** The sandbox for the comma-separated value of {@code sandbox.allowed.directories} (null or blank: none). */
	public static DuckDBSandbox fromProperty(String commaSeparated) {
		List<String> dirs = new ArrayList<>();
		if (commaSeparated != null) {
			for (String part : commaSeparated.split(",")) {
				if (!part.isBlank()) {
					dirs.add(part.trim());
				}
			}
		}
		return new DuckDBSandbox(dirs);
	}

	/**
	 * The directories a user may reach: its S3 storage scopes ({@code s3://bucket} or {@code s3://bucket/folder}) plus
	 * the configured ones, each ending with {@code /} (so {@code s3://bucket} does not also allow
	 * {@code s3://bucket-other}), sorted and without duplicates. Two users with the same storages get equal lists.
	 */
	public List<String> allowedDirectories(Collection<String> storageScopes) {
		TreeSet<String> all = new TreeSet<>(configuredDirectories);
		all.addAll(normalize(storageScopes));
		return List.copyOf(all);
	}

	/** True when one of the directories is an S3 location, so the database needs the httpfs extension. */
	public static boolean needsHttpfs(Collection<String> allowedDirectories) {
		for (String dir : allowedDirectories) {
			if (dir.startsWith("s3://") || dir.startsWith("s3a://") || dir.startsWith("s3n://")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Locks the database of {@code conn} down (see the class comment) and checks it worked.
	 *
	 * @throws SQLException if a statement fails or a setting does not have the required value afterwards; the caller
	 *                      must then close the connection and not hand it out
	 */
	public void apply(Connection conn, List<String> allowedDirectories) throws SQLException {
		for (String sql : statements(allowedDirectories, allowedFiles)) {
			// one Statement per SQL: the DuckDB JDBC driver closes a Statement whose execute() fails
			try (Statement stmt = conn.createStatement()) {
				stmt.execute(sql);
			}
		}
		verify(conn);
	}

	/** The statements {@link #apply} runs, in order; {@code lock_configuration} is always last. */
	static List<String> statements(List<String> allowedDirectories, List<String> allowedFiles) {
		List<String> sql = new ArrayList<>();
		sql.add("SET allow_persistent_secrets = false");
		sql.add("SET autoinstall_known_extensions = false");
		sql.add("SET autoload_known_extensions = false");
		sql.add("SET allow_community_extensions = false");
		sql.add("SET allowed_directories = " + listLiteral(allowedDirectories));
		sql.add("SET allowed_paths = " + listLiteral(allowedFiles));
		sql.add("SET enable_external_access = false");
		sql.add("SET lock_configuration = true");
		return sql;
	}

	private static void verify(Connection conn) throws SQLException {
		Map<String, String> actual = new TreeMap<>();
		try (Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT name, value FROM duckdb_settings() WHERE name IN ("
						+ String.join(", ", REQUIRED.keySet().stream().map(DuckDBSandbox::literal).toList()) + ")")) {
			while (rs.next()) {
				actual.put(rs.getString(1), rs.getString(2));
			}
		}
		for (Map.Entry<String, String> required : REQUIRED.entrySet()) {
			String value = actual.get(required.getKey());
			if (value == null || !value.trim().equalsIgnoreCase(required.getValue())) {
				throw new SQLException("DuckDB sandbox not in place: " + required.getKey() + " is " + value
						+ " instead of " + required.getValue());
			}
		}
	}

	/** A DuckDB list of strings, for example {@code ['s3://a/', '/data/']}. */
	static String listLiteral(List<String> values) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.size(); i++) {
			sb.append(i == 0 ? "" : ", ").append(literal(values.get(i)));
		}
		return sb.append(']').toString();
	}

	private static String literal(String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	/** Trimmed, blank entries dropped, each ending with {@code /}. */
	private static List<String> normalize(Collection<String> dirs) {
		TreeSet<String> out = new TreeSet<>();
		if (dirs != null) {
			for (String dir : dirs) {
				if (dir == null || dir.isBlank()) {
					continue;
				}
				String d = dir.trim().replace('\\', '/');
				out.add(d.endsWith("/") ? d : d + "/");
			}
		}
		return List.copyOf(out);
	}
}
