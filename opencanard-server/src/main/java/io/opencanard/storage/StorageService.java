package io.opencanard.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBConnection;

/**
 * The S3 storages of each user. A user only ever sees and changes their own: the login always comes from the
 * authenticated request, never from the body.
 * <p>
 * Rules: names are unique per user and cannot be renamed; the secret is stored encrypted and is never returned;
 * on update an empty secret keeps the stored one.
 */
public class StorageService {

	private static final Logger logger = LogManager.getLogger(StorageService.class);

	/** The user has no storage with that name. */
	public static class NotFoundException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public NotFoundException(String message) {
			super(message);
		}
	}

	/** The user already has a storage with that name. */
	public static class AlreadyExistsException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public AlreadyExistsException(String message) {
			super(message);
		}
	}

	private final Connection metadatadb;
	private final SecretBox secrets;
	private final StorageRepository repository = new StorageRepository();
	/** Secrets created in DuckDB for each login, so the ones of removed storages can be dropped. */
	private final Map<String, Set<String>> applied = new ConcurrentHashMap<>();

	public StorageService(Connection metadatadb, SecretBox secrets) {
		this.metadatadb = metadatadb;
		this.secrets = secrets;
	}

	public List<StorageConfig> list(String login) throws SQLException {
		try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
			return repository.list(c, login);
		}
	}

	public synchronized StorageConfig create(String login, StorageRequest request) throws SQLException {
		StorageValidator.Clean clean = StorageValidator.validate(request, true);
		try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
			if (repository.find(c, login, clean.name) != null) {
				throw new AlreadyExistsException("You already have a storage named '" + clean.name + "'");
			}
			requireDistinctScope(c, login, clean.name, clean);
			repository.insert(c, login, clean, secrets.encrypt(clean.secret, context(login, clean.name)));
			logger.info("[storage] '{}' added storage '{}' ({})", login, clean.name, clean.bucket);
			return repository.find(c, login, clean.name);
		}
	}

	public synchronized StorageConfig update(String login, String name, StorageRequest request) throws SQLException {
		requireName(name);
		StorageValidator.Clean clean = StorageValidator.validate(request, false);
		try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
			requireDistinctScope(c, login, name, clean);
			if (clean.secret == null) {
				StorageConfig existing = repository.find(c, login, name);
				if (existing != null && !isReadable(existing)) {
					throw new IllegalArgumentException(
							"The saved secret cannot be read any more because the storage key changed. Type the secret again");
				}
			}
			String encrypted = clean.secret == null ? null : secrets.encrypt(clean.secret, context(login, name));
			if (repository.update(c, login, name, clean, encrypted) == 0) {
				throw notFound(name);
			}
			logger.info("[storage] '{}' changed storage '{}'", login, name);
			return repository.find(c, login, name);
		}
	}

	public synchronized void delete(String login, String name) throws SQLException {
		requireName(name);
		try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
			if (repository.delete(c, login, name) == 0) {
				throw notFound(name);
			}
			logger.info("[storage] '{}' removed storage '{}'", login, name);
		}
	}

	/**
	 * The decrypted secret, for the code that will open the storage. Never expose it through the REST API.
	 *
	 * @throws IllegalStateException if the storage key changed since it was saved
	 */
	public String secretFor(String login, String name) throws SQLException {
		try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
			StorageConfig config = repository.find(c, login, name);
			if (config == null) {
				throw notFound(name);
			}
			return secrets.decrypt(config.getSecretEncrypted(), context(login, name));
		}
	}

	/**
	 * Makes the storages of {@code login} usable from SQL on {@code target}: one temporary DuckDB S3 secret per
	 * storage (see {@link StorageSecrets}). Secrets of storages removed since the last call are dropped.
	 * <p>
	 * DuckDB keeps secrets per database, not per connection, so they are visible to every connection to the
	 * same database; that is the user's own file, except for admins, who share the metadata database.
	 * <p>
	 * Needs the DuckDB {@code httpfs} extension, which is installed on first use (internet access required).
	 */
	public void apply(Connection target, String login) throws SQLException {
		List<StorageConfig> storages = list(login);
		Set<String> previous = applied.getOrDefault(login, Set.of());
		if (storages.isEmpty() && previous.isEmpty()) {
			return;
		}
		Set<String> current = new HashSet<>();
		List<String> unreadable = new java.util.ArrayList<>();
		try (Statement st = target.createStatement()) {
			if (!storages.isEmpty()) {
				loadHttpfs(st);
			}
			for (StorageConfig s : storages) {
				String plain;
				try {
					plain = secrets.decrypt(s.getSecretEncrypted(), context(login, s.getName()));
				} catch (IllegalStateException e) {
					logger.warn("[storage] '{}' of '{}' skipped: {}", s.getName(), login, e.getMessage());
					unreadable.add(s.getName());
					continue;
				}
				st.execute(StorageSecrets.createSql(s, plain));
				current.add(s.getName());
			}
			for (String gone : previous) {
				if (!current.contains(gone)) {
					st.execute(StorageSecrets.dropSql(login, gone));
				}
			}
		}
		Set<String> before = applied.put(login, current);
		if (before == null || !before.equals(current)) {
			StringBuilder scopes = new StringBuilder();
			for (StorageConfig s : storages) {
				if (current.contains(s.getName())) {
					scopes.append(scopes.length() == 0 ? "" : ", ").append(StorageSecrets.scope(s));
				}
			}
			logger.info("[storage] registered {} S3 storage(s) for '{}' on the connection: {}", current.size(), login,
					scopes);
		}
		if (!unreadable.isEmpty()) {
			throw new SQLException("The secret of " + String.join(", ", unreadable)
					+ " cannot be read because the storage key changed. Edit the storage and type the secret again");
		}
	}

	/** False when the stored secret cannot be decrypted with the current storage key. */
	public boolean isReadable(StorageConfig s) {
		try {
			secrets.decrypt(s.getSecretEncrypted(), context(s.getLogin(), s.getName()));
			return true;
		} catch (IllegalStateException e) {
			return false;
		}
	}

	private static void loadHttpfs(Statement st) throws SQLException {
		try {
			st.execute("LOAD httpfs");
		} catch (SQLException notInstalled) {
			logger.info("[storage] installing the DuckDB httpfs extension");
			st.execute("INSTALL httpfs");
			st.execute("LOAD httpfs");
		}
	}

	/**
	 * A query path says only {@code s3://bucket/folder/...}, so two storages of one user with the same bucket and
	 * folder could not be told apart. A folder inside another storage's scope is fine: the longest scope wins.
	 */
	private void requireDistinctScope(Connection c, String login, String name, StorageValidator.Clean clean)
			throws SQLException {
		String scope = StorageSecrets.scope(clean.bucket, clean.pathPrefix);
		for (StorageConfig other : repository.list(c, login)) {
			if (!other.getName().equals(name) && StorageSecrets.scope(other).equals(scope)) {
				throw new IllegalArgumentException("Your storage '" + other.getName() + "' already uses " + scope
						+ ". A query path could not tell them apart: use a different bucket or folder");
			}
		}
	}

	static String context(String login, String name) {
		return login + "/" + name;
	}

	private static void requireName(String name) {
		if (!StorageValidator.isValidName(name)) {
			throw new IllegalArgumentException("Invalid storage name");
		}
	}

	private static NotFoundException notFound(String name) {
		return new NotFoundException("You have no storage named '" + name + "'");
	}
}
