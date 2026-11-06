package io.opencanard.storage;

import java.sql.Timestamp;

/** One row of {@code opencanard_storages}, with the secret still encrypted. */
public final class StorageConfig {

	public static final String URL_STYLE_VHOST = "vhost";
	public static final String URL_STYLE_PATH = "path";

	private final String login;
	private final String name;
	private final String bucket;
	private final String region;
	private final String endpoint;
	private final String urlStyle;
	private final boolean useSsl;
	private final String pathPrefix;
	private final String accessKeyId;
	private final String secretEncrypted;
	private final Timestamp createdAt;
	private final Timestamp updatedAt;

	public StorageConfig(String login, String name, String bucket, String region, String endpoint, String urlStyle,
			boolean useSsl, String pathPrefix, String accessKeyId, String secretEncrypted, Timestamp createdAt,
			Timestamp updatedAt) {
		this.login = login;
		this.name = name;
		this.bucket = bucket;
		this.region = region;
		this.endpoint = endpoint;
		this.urlStyle = urlStyle;
		this.useSsl = useSsl;
		this.pathPrefix = pathPrefix;
		this.accessKeyId = accessKeyId;
		this.secretEncrypted = secretEncrypted;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public String getLogin() { return login; }
	public String getName() { return name; }
	public String getBucket() { return bucket; }
	public String getRegion() { return region; }
	/** {@code null} for AWS S3. */
	public String getEndpoint() { return endpoint; }
	public String getUrlStyle() { return urlStyle; }
	public boolean isUseSsl() { return useSsl; }
	/** Empty, or a folder ending in {@code /}. */
	public String getPathPrefix() { return pathPrefix; }
	public String getAccessKeyId() { return accessKeyId; }
	public String getSecretEncrypted() { return secretEncrypted; }

	/** A public bucket: no access key ID and no secret, so requests are not signed. */
	public boolean isAnonymous() { return accessKeyId == null || accessKeyId.isEmpty(); }
	public Timestamp getCreatedAt() { return createdAt; }
	public Timestamp getUpdatedAt() { return updatedAt; }

	/** {@code s3://bucket/prefix}, for display. */
	public String url() {
		return "s3://" + bucket + (pathPrefix.isEmpty() ? "" : "/" + pathPrefix);
	}
}
