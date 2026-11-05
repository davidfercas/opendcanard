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
 * on update an empty secret keeps the stored one. A storage without access key ID and secret is a public bucket:
 * it keeps an empty key and an empty secret, and its requests are not signed.
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

	/** The storage could not be reached or S3 refused the request; the message is meant for the user. */
	public static class RemoteException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public RemoteException(String message) {
			super(message);
		}
	}

	/** One level of a bucket, with paths relative to the storage's own folder. */
	public static final class Browsed {
		public final StorageConfig storage;
		public final String path;
		public final S3Client.Listing listing;

		Browsed(StorageConfig storage, String path, S3Client.Listing listing) {
			this.storage = storage;
			this.path = path;
			this.listing = listing;
		}
	}

	public static final int PAGE_SIZE = 200;

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
			repository.insert(c, login, clean, encrypt(clean, login, clean.name));
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
				if (existing != null && existing.isAnonymous()) {
					throw new IllegalArgumentException(
							"This storage has no saved secret: type the secret access key, or leave both fields empty for a public bucket");
				}
				if (existing != null && !isReadable(existing)) {
					throw new IllegalArgumentException(
							"The saved secret cannot be read any more because the storage key changed. Type the secret again");
				}
			}
			String encrypted = clean.secret == null ? null : encrypt(clean, login, name);
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
	 * Lists one level of the bucket of a storage, below its folder.
	 *
	 * @param path  folder relative to the storage's own folder, empty for the top
	 * @param token continuation token of the previous page, or null
	 */
	public Browsed browse(String login, String name, String path, String token) throws SQLException {
		requireName(name);
		String relative = relativePath(path);
		if (token != null && token.length() > 2048) {
			throw new IllegalArgumentException("Invalid page token");
		}
		StorageConfig config;
		String secret;
		try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
			config = repository.find(c, login, name);
		}
		if (config == null) {
			throw notFound(name);
		}
		try {
			secret = decrypt(config);
		} catch (IllegalStateException e) {
			throw new RemoteException("The saved secret cannot be read any more because the storage key changed. Edit the storage and type the secret again");
		}
		S3Client client = new S3Client(config.getEndpoint(), config.getRegion(), config.getBucket(),
				config.getUrlStyle(), config.isUseSsl(), config.getAccessKeyId(), secret);
		return new Browsed(config, relative, remote(client, config.getPathPrefix() + relative, token, PAGE_SIZE));
	}

	/**
	 * Tries the values of the form without saving them: lists the top of the bucket. With an access key ID and an
	 * empty secret the one stored under the same name is used; with neither the bucket is read without signing.
	 *
	 * @return a sentence saying what was found
	 */
	public String test(String login, StorageRequest request) throws SQLException {
		StorageValidator.Clean clean = StorageValidator.validate(request, false);
		String secret = clean.secret;
		if (secret == null) {
			String name = request.name == null ? "" : request.name.trim();
			StorageConfig existing = null;
			if (StorageValidator.isValidName(name)) {
				try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
					existing = repository.find(c, login, name);
				}
			}
			if (existing == null || existing.isAnonymous()) {
				throw new IllegalArgumentException(
						"Type the secret access key to test the connection, or leave both fields empty for a public bucket");
			}
			try {
				secret = decrypt(existing);
			} catch (IllegalStateException e) {
				throw new IllegalArgumentException("The saved secret cannot be read any more. Type the secret again");
			}
		}
		S3Client client = new S3Client(clean.endpoint, clean.region, clean.bucket, clean.urlStyle, clean.useSsl,
				clean.accessKeyId, secret);
		S3Client.Listing top = remote(client, clean.pathPrefix, null, 50);
		int n = top.folders.size() + top.files.size();
		return "Connected to " + (clean.anonymous ? "public " : "") + "bucket '" + clean.bucket + "'. "
				+ (n == 0 ? "It is empty at this level."
						: "Found " + top.folders.size() + " folder(s) and " + top.files.size() + " file(s)"
								+ (top.truncated ? " or more" : "") + " at the top.");
	}

	private static S3Client.Listing remote(S3Client client, String prefix, String token, int pageSize) {
		try {
			return client.list(prefix, token, pageSize);
		} catch (S3Client.S3Exception e) {
			throw new RemoteException(e.getMessage() + hint(client, e));
		} catch (java.io.IOException e) {
			throw new RemoteException("Cannot reach the storage: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RemoteException("The request was interrupted");
		}
	}

	private static String hint(S3Client client, S3Client.S3Exception e) {
		switch (e.getCode()) {
		case "SignatureDoesNotMatch":
			return " (check the secret access key and the region)";
		case "InvalidAccessKeyId":
			return " (check the access key ID)";
		case "AccessDenied":
			return client.isAnonymous() ? " (the bucket does not allow public listing; add an access key)"
					: " (the key has no permission to list this bucket or folder)";
		case "NoSuchBucket":
			return " (check the bucket name)";
		case "PermanentRedirect":
		case "AuthorizationHeaderMalformed":
			return " (check the region)";
		default:
			return "";
		}
	}

	/** A folder below the storage's own, without {@code ..}; empty or ending in {@code /}. */
	static String relativePath(String path) {
		String p = path == null ? "" : path.trim();
		while (p.startsWith("/")) {
			p = p.substring(1);
		}
		if (p.isEmpty()) {
			return "";
		}
		if (p.length() > 1024) {
			throw new IllegalArgumentException("The folder path is too long");
		}
		for (String part : p.split("/", -1)) {
			if (part.equals("..") || part.equals(".")) {
				throw new IllegalArgumentException("The folder path cannot contain '.' or '..'");
			}
		}
		for (int i = 0; i < p.length(); i++) {
			if (Character.isISOControl(p.charAt(i))) {
				throw new IllegalArgumentException("The folder path contains a control character");
			}
		}
		return p.endsWith("/") ? p : p + "/";
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
			return decrypt(config);
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
					plain = decrypt(s);
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
			decrypt(s);
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

	/** The plain secret of a storage; empty for a public bucket. */
	private String decrypt(StorageConfig s) {
		if (s.isAnonymous()) {
			return "";
		}
		return secrets.decrypt(s.getSecretEncrypted(), context(s.getLogin(), s.getName()));
	}

	/** What goes in {@code secret_access_key_enc}: empty for a public bucket, otherwise the encrypted secret. */
	private String encrypt(StorageValidator.Clean clean, String login, String name) {
		return clean.anonymous ? "" : secrets.encrypt(clean.secret, context(login, name));
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
