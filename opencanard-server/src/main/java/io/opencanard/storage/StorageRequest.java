package io.opencanard.storage;

/** Body of the create and update calls. Every field is optional here; {@link StorageValidator} decides what is required. */
public class StorageRequest {

	/** Label chosen by the user, unique per user. Cannot be changed after creation. */
	public String name;
	/** Bucket name. */
	public String bucket;
	/** AWS region, for example {@code eu-west-1}. Optional with a custom endpoint. */
	public String region;
	/** {@code host[:port]} of an S3-compatible service (MinIO, Ceph...). Empty for AWS S3. */
	public String endpoint;
	/** {@code vhost} or {@code path}. Default: {@code vhost} for AWS, {@code path} with a custom endpoint. */
	public String urlStyle;
	/** Use https. Default true. */
	public Boolean useSsl;
	/** Only this folder of the bucket is used. Optional. */
	public String pathPrefix;
	public String accessKeyId;
	/** Required on create. On update, empty or missing keeps the stored secret. */
	public String secretAccessKey;

	public StorageRequest() {
	}
}
