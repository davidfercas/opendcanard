package io.opencanard.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

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
			repository.insert(c, login, clean, secrets.encrypt(clean.secret, context(login, clean.name)));
			logger.info("[storage] '{}' added storage '{}' ({})", login, clean.name, clean.bucket);
			return repository.find(c, login, clean.name);
		}
	}

	public synchronized StorageConfig update(String login, String name, StorageRequest request) throws SQLException {
		requireName(name);
		StorageValidator.Clean clean = StorageValidator.validate(request, false);
		try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
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
