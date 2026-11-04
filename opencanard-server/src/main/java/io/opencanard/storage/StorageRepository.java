package io.opencanard.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The table {@code opencanard_storages} of the metadata database: the S3 storages every user has configured.
 * <pre>
 * login                  VARCHAR    the owner
 * name                   VARCHAR    label chosen by the user, unique per login
 * bucket, region         VARCHAR
 * endpoint               VARCHAR    host[:port] of an S3-compatible service; NULL for AWS S3
 * url_style              VARCHAR    'vhost' or 'path'
 * use_ssl                BOOLEAN
 * path_prefix            VARCHAR    '' or a folder ending in '/'
 * access_key_id          VARCHAR
 * secret_access_key_enc  VARCHAR    encrypted by {@link SecretBox}, never returned by the API
 * created_at, updated_at TIMESTAMP  set with now()
 * </pre>
 * Every method takes the connection to use, so callers decide which (duplicate) connection runs it.
 */
public class StorageRepository {

	public static final String CREATE_TABLE = "CREATE TABLE opencanard_storages(" //
			+ "login VARCHAR NOT NULL, " //
			+ "name VARCHAR NOT NULL, " //
			+ "bucket VARCHAR NOT NULL, " //
			+ "region VARCHAR NOT NULL, " //
			+ "endpoint VARCHAR, " //
			+ "url_style VARCHAR NOT NULL DEFAULT 'vhost', " //
			+ "use_ssl BOOLEAN NOT NULL DEFAULT true, " //
			+ "path_prefix VARCHAR NOT NULL DEFAULT '', " //
			+ "access_key_id VARCHAR NOT NULL, " //
			+ "secret_access_key_enc VARCHAR NOT NULL, " //
			+ "created_at TIMESTAMP NOT NULL DEFAULT now(), " //
			+ "updated_at TIMESTAMP NOT NULL DEFAULT now(), " //
			+ "PRIMARY KEY (login, name))";

	private static final String COLUMNS = "login, name, bucket, region, endpoint, url_style, use_ssl, path_prefix, "
			+ "access_key_id, secret_access_key_enc, created_at, updated_at";

	public List<StorageConfig> list(Connection db, String login) throws SQLException {
		List<StorageConfig> out = new ArrayList<>();
		try (PreparedStatement ps = db.prepareStatement(
				"SELECT " + COLUMNS + " FROM main.opencanard_storages WHERE login = ? ORDER BY lower(name), name")) {
			ps.setString(1, login);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(read(rs));
				}
			}
		}
		return out;
	}

	public StorageConfig find(Connection db, String login, String name) throws SQLException {
		try (PreparedStatement ps = db.prepareStatement(
				"SELECT " + COLUMNS + " FROM main.opencanard_storages WHERE login = ? AND name = ?")) {
			ps.setString(1, login);
			ps.setString(2, name);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? read(rs) : null;
			}
		}
	}

	public void insert(Connection db, String login, StorageValidator.Clean c, String secretEncrypted)
			throws SQLException {
		try (PreparedStatement ps = db.prepareStatement("INSERT INTO main.opencanard_storages(" + COLUMNS
				+ ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())")) {
			ps.setString(1, login);
			ps.setString(2, c.name);
			ps.setString(3, c.bucket);
			ps.setString(4, c.region);
			ps.setString(5, c.endpoint);
			ps.setString(6, c.urlStyle);
			ps.setBoolean(7, c.useSsl);
			ps.setString(8, c.pathPrefix);
			ps.setString(9, c.accessKeyId);
			ps.setString(10, secretEncrypted);
			ps.executeUpdate();
		}
	}

	/** Updates everything but login, name and created_at. A null {@code secretEncrypted} keeps the stored secret. */
	public int update(Connection db, String login, String name, StorageValidator.Clean c, String secretEncrypted)
			throws SQLException {
		try (PreparedStatement ps = db.prepareStatement("UPDATE main.opencanard_storages SET bucket = ?, region = ?, "
				+ "endpoint = ?, url_style = ?, use_ssl = ?, path_prefix = ?, access_key_id = ?, "
				+ "secret_access_key_enc = coalesce(?, secret_access_key_enc), updated_at = now() "
				+ "WHERE login = ? AND name = ?")) {
			ps.setString(1, c.bucket);
			ps.setString(2, c.region);
			ps.setString(3, c.endpoint);
			ps.setString(4, c.urlStyle);
			ps.setBoolean(5, c.useSsl);
			ps.setString(6, c.pathPrefix);
			ps.setString(7, c.accessKeyId);
			ps.setString(8, secretEncrypted);
			ps.setString(9, login);
			ps.setString(10, name);
			return ps.executeUpdate();
		}
	}

	public int delete(Connection db, String login, String name) throws SQLException {
		try (PreparedStatement ps = db
				.prepareStatement("DELETE FROM main.opencanard_storages WHERE login = ? AND name = ?")) {
			ps.setString(1, login);
			ps.setString(2, name);
			return ps.executeUpdate();
		}
	}

	private static StorageConfig read(ResultSet rs) throws SQLException {
		return new StorageConfig(rs.getString("login"), rs.getString("name"), rs.getString("bucket"),
				rs.getString("region"), rs.getString("endpoint"), rs.getString("url_style"), rs.getBoolean("use_ssl"),
				rs.getString("path_prefix"), rs.getString("access_key_id"), rs.getString("secret_access_key_enc"),
				rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
	}
}
