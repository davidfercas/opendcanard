package io.opencanard.storage;

/**
 * Builds the DuckDB statements that make a storage usable from SQL: a temporary S3 secret whose scope is
 * {@code s3://bucket/folder}, so a query on {@code s3://bucket/folder/file.csv} uses that storage's endpoint and
 * credentials instead of AWS defaults. JDK only.
 */
public final class StorageSecrets {

	private StorageSecrets() {
	}

	/** Name of the DuckDB secret of a storage: unique per user and storage. */
	public static String secretName(String login, String storageName) {
		return "opencanard_" + login + "__" + storageName;
	}

	/** {@code s3://bucket} or {@code s3://bucket/folder/} (DuckDB takes one string as scope). */
	public static String scope(StorageConfig s) {
		return scope(s.getBucket(), s.getPathPrefix());
	}

	public static String scope(String bucket, String pathPrefix) {
		return "s3://" + bucket + (pathPrefix.isEmpty() ? "" : "/" + pathPrefix);
	}

	public static String createSql(StorageConfig s, String plainSecret) {
		StringBuilder sql = new StringBuilder("CREATE OR REPLACE TEMPORARY SECRET ")
				.append(identifier(secretName(s.getLogin(), s.getName())))
				.append(" (TYPE S3");
		sql.append(", KEY_ID ").append(literal(s.getAccessKeyId()));
		sql.append(", SECRET ").append(literal(plainSecret));
		sql.append(", REGION ").append(literal(s.getRegion()));
		if (s.getEndpoint() != null && !s.getEndpoint().isEmpty()) {
			sql.append(", ENDPOINT ").append(literal(s.getEndpoint()));
		}
		sql.append(", URL_STYLE ").append(literal(s.getUrlStyle()));
		sql.append(", USE_SSL ").append(s.isUseSsl() ? "true" : "false");
		sql.append(", SCOPE ").append(literal(scope(s)));
		return sql.append(")").toString();
	}

	public static String dropSql(String login, String storageName) {
		return "DROP TEMPORARY SECRET IF EXISTS " + identifier(secretName(login, storageName));
	}

	static String literal(String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	static String identifier(String value) {
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}
}
