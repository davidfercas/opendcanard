package io.opencanard.storage;

import java.util.regex.Pattern;

/**
 * Checks and normalizes what the user typed in the storage form. JDK only. Every message says what to fix and
 * is shown as is in the form.
 */
public final class StorageValidator {

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
	private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
	private static final Pattern REGION = Pattern.compile("[a-z0-9-]{2,30}");
	private static final Pattern ENDPOINT = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:\\d{1,5})?");
	private static final Pattern ACCESS_KEY = Pattern.compile("[A-Za-z0-9+/=_.@-]{4,128}");

	public static final String DEFAULT_REGION = "us-east-1";
	public static final int MAX_SECRET = 256;
	public static final int MAX_PREFIX = 512;

	private StorageValidator() {
	}

	/** What the form fields become once checked. The secret is {@code null} when an update keeps the stored one. */
	public static final class Clean {
		public String name;
		public String bucket;
		public String region;
		public String endpoint;
		public String urlStyle;
		public boolean useSsl;
		public String pathPrefix;
		public String accessKeyId;
		public String secret;
	}

	/**
	 * @param creating true for a new storage (name and secret are required)
	 * @throws IllegalArgumentException with a message for the user
	 */
	public static Clean validate(StorageRequest in, boolean creating) {
		if (in == null) {
			throw new IllegalArgumentException("The request is empty");
		}
		Clean out = new Clean();

		out.name = trim(in.name);
		if (creating && !NAME.matcher(out.name).matches()) {
			throw new IllegalArgumentException(
					"Name must start with a letter or digit and use only letters, digits, '.', '_' and '-' (up to 64 characters)");
		}

		out.bucket = trim(in.bucket);
		if (!BUCKET.matcher(out.bucket).matches() || out.bucket.contains("..")) {
			throw new IllegalArgumentException(
					"Bucket must be 3 to 63 characters: lowercase letters, digits, '.' and '-', starting and ending with a letter or digit");
		}

		out.endpoint = trim(in.endpoint);
		if (out.endpoint.isEmpty()) {
			out.endpoint = null;
		} else if (!ENDPOINT.matcher(out.endpoint).matches()) {
			throw new IllegalArgumentException(
					"Endpoint must be a host name with an optional port, such as minio.example.com:9000, without http:// or a path");
		}

		out.region = trim(in.region).toLowerCase();
		if (out.region.isEmpty()) {
			if (out.endpoint == null) {
				throw new IllegalArgumentException("Region is required for AWS S3, for example eu-west-1");
			}
			out.region = DEFAULT_REGION;
		} else if (!REGION.matcher(out.region).matches()) {
			throw new IllegalArgumentException("Region must look like eu-west-1");
		}

		String style = trim(in.urlStyle).toLowerCase();
		if (style.isEmpty()) {
			style = out.endpoint == null ? StorageConfig.URL_STYLE_VHOST : StorageConfig.URL_STYLE_PATH;
		}
		if (!style.equals(StorageConfig.URL_STYLE_VHOST) && !style.equals(StorageConfig.URL_STYLE_PATH)) {
			throw new IllegalArgumentException("URL style must be 'vhost' or 'path'");
		}
		out.urlStyle = style;
		out.useSsl = in.useSsl == null || in.useSsl;

		out.pathPrefix = prefix(in.pathPrefix);

		out.accessKeyId = trim(in.accessKeyId);
		if (!ACCESS_KEY.matcher(out.accessKeyId).matches()) {
			throw new IllegalArgumentException("Access key ID must be 4 to 128 characters without spaces");
		}

		String secret = in.secretAccessKey;
		if (secret == null || secret.isEmpty()) {
			if (creating) {
				throw new IllegalArgumentException("Secret access key is required");
			}
			out.secret = null;
		} else {
			if (secret.length() > MAX_SECRET) {
				throw new IllegalArgumentException("Secret access key is longer than " + MAX_SECRET + " characters");
			}
			for (int i = 0; i < secret.length(); i++) {
				if (Character.isISOControl(secret.charAt(i))) {
					throw new IllegalArgumentException(
							"Secret access key contains a line break or control character; paste it again without trailing spaces or lines");
				}
			}
			out.secret = secret;
		}
		return out;
	}

	/** True for a name {@link #validate} accepts as a storage name (used on the update and delete paths). */
	public static boolean isValidName(String name) {
		return name != null && NAME.matcher(name).matches();
	}

	private static String prefix(String raw) {
		String p = trim(raw).replace('\\', '/');
		while (p.startsWith("/")) {
			p = p.substring(1);
		}
		if (p.isEmpty()) {
			return "";
		}
		if (p.length() > MAX_PREFIX) {
			throw new IllegalArgumentException("Folder is longer than " + MAX_PREFIX + " characters");
		}
		for (String part : p.split("/", -1)) {
			if (part.equals("..") || part.equals(".")) {
				throw new IllegalArgumentException("Folder cannot contain '.' or '..' parts");
			}
		}
		for (int i = 0; i < p.length(); i++) {
			if (Character.isISOControl(p.charAt(i))) {
				throw new IllegalArgumentException("Folder contains a control character");
			}
		}
		return p.endsWith("/") ? p : p + "/";
	}

	private static String trim(String s) {
		return s == null ? "" : s.trim();
	}
}
