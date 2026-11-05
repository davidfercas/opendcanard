package io.opencanard.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Lists the content of a bucket with the S3 {@code ListObjectsV2} call (AWS Signature V4), for the storage tree
 * of the web console and the "Test connection" button. JDK only: it needs neither the AWS SDK nor DuckDB's
 * {@code httpfs}, and it shows the exact error S3 answers ({@code SignatureDoesNotMatch}, {@code AccessDenied},
 * {@code NoSuchBucket}...).
 * <p>
 * Redirects are never followed, responses are read up to {@link #MAX_RESPONSE_BYTES}, and the XML parser refuses
 * DTDs, so a hostile endpoint cannot make the server fetch other URLs or expand entities.
 */
public final class S3Client {

	public static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
	private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
	private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
			.withZone(ZoneOffset.UTC);

	/** S3 answered with an error. */
	public static final class S3Exception extends Exception {
		private static final long serialVersionUID = 1L;
		private final int status;
		private final String code;

		public S3Exception(int status, String code, String message) {
			super(message);
			this.status = status;
			this.code = code;
		}

		public int getStatus() {
			return status;
		}

		/** The S3 error code such as {@code AccessDenied}, or empty. */
		public String getCode() {
			return code;
		}
	}

	/** An object in a listing. */
	public static final class FileEntry {
		public final String key;
		public final String name;
		public final long size;
		public final String modified;

		FileEntry(String key, String name, long size, String modified) {
			this.key = key;
			this.name = name;
			this.size = size;
			this.modified = modified;
		}
	}

	/** One page of a listing: the "folders" and objects directly under a prefix. */
	public static final class Listing {
		/** Full prefixes, each ending in {@code /}. */
		public final List<String> folders = new ArrayList<>();
		public final List<FileEntry> files = new ArrayList<>();
		public boolean truncated;
		public String nextToken;
	}

	private final String endpoint;
	private final String region;
	private final String bucket;
	private final boolean pathStyle;
	private final boolean useSsl;
	private final String accessKeyId;
	private final String secret;
	private final HttpClient http;

	/**
	 * @param endpoint {@code host[:port]} of a compatible service, or null for AWS S3
	 * @param urlStyle {@code path} or {@code vhost}
	 */
	public S3Client(String endpoint, String region, String bucket, String urlStyle, boolean useSsl,
			String accessKeyId, String secret) {
		this.endpoint = endpoint == null || endpoint.isEmpty() ? null : endpoint;
		this.region = region;
		this.bucket = bucket;
		this.pathStyle = StorageConfig.URL_STYLE_PATH.equals(urlStyle);
		this.useSsl = useSsl;
		this.accessKeyId = accessKeyId;
		this.secret = secret;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NEVER).build();
	}

	/**
	 * @param prefix    full key prefix, empty or ending in {@code /}
	 * @param token     continuation token of the previous page, or null
	 * @param maxKeys   page size
	 * @throws S3Exception  S3 refused the request
	 * @throws IOException  the endpoint cannot be reached, or the answer is not S3's
	 */
	public Listing list(String prefix, String token, int maxKeys) throws S3Exception, IOException, InterruptedException {
		TreeMap<String, String> query = new TreeMap<>();
		query.put("list-type", "2");
		query.put("delimiter", "/");
		query.put("max-keys", String.valueOf(maxKeys));
		if (prefix != null && !prefix.isEmpty()) {
			query.put("prefix", prefix);
		}
		if (token != null && !token.isEmpty()) {
			query.put("continuation-token", token);
		}

		String authority = authority();
		String path = pathStyle ? "/" + bucket : "/";
		String amzDate = AMZ_DATE.format(Instant.now());
		String authorization = authorization("GET", authority, path, query, amzDate, region, accessKeyId, secret);

		URI uri = URI.create((useSsl ? "https://" : "http://") + authority + path + "?" + canonicalQuery(query));
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET()
				.header("x-amz-date", amzDate).header("x-amz-content-sha256", EMPTY_SHA256)
				.header("Authorization", authorization).build();

		HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		byte[] body;
		try (InputStream in = response.body()) {
			body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
		}
		if (body.length > MAX_RESPONSE_BYTES) {
			throw new IOException("The answer of " + authority + " is larger than " + MAX_RESPONSE_BYTES + " bytes");
		}
		if (response.statusCode() != 200) {
			throw error(response.statusCode(), body, authority);
		}
		return parseListing(body, prefix == null ? "" : prefix, authority);
	}

	/** Host (with port if not the default one) the request goes to, and the one that is signed. */
	String authority() {
		String host = endpoint != null ? endpoint : "s3." + region + ".amazonaws.com";
		if (useSsl && host.endsWith(":443") || !useSsl && host.endsWith(":80")) {
			host = host.substring(0, host.lastIndexOf(':'));
		}
		return pathStyle ? host : bucket + "." + host;
	}

	// ---------- AWS Signature Version 4 ----------

	/** The {@code Authorization} header for a request without body whose signed headers are host, x-amz-content-sha256 and x-amz-date. */
	static String authorization(String method, String authority, String path, TreeMap<String, String> query,
			String amzDate, String region, String accessKeyId, String secret) {
		String date = amzDate.substring(0, 8);
		String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
		String canonicalRequest = method + "\n" + path + "\n" + canonicalQuery(query) + "\n" + "host:" + authority
				+ "\n" + "x-amz-content-sha256:" + EMPTY_SHA256 + "\n" + "x-amz-date:" + amzDate + "\n\n"
				+ signedHeaders + "\n" + EMPTY_SHA256;
		String scope = date + "/" + region + "/s3/aws4_request";
		String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest));

		byte[] k = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
		k = hmac(k, region);
		k = hmac(k, "s3");
		k = hmac(k, "aws4_request");
		String signature = hex(hmac(k, stringToSign));

		return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope + ", SignedHeaders=" + signedHeaders
				+ ", Signature=" + signature;
	}

	static String canonicalQuery(Map<String, String> sorted) {
		TreeMap<String, String> encoded = new TreeMap<>();
		for (Map.Entry<String, String> e : sorted.entrySet()) {
			encoded.put(encode(e.getKey()), encode(e.getValue()));
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : encoded.entrySet()) {
			sb.append(sb.length() == 0 ? "" : "&").append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.toString();
	}

	/** RFC 3986 percent-encoding: only A-Z a-z 0-9 - _ . ~ stay as they are. */
	static String encode(String s) {
		StringBuilder sb = new StringBuilder();
		for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xff;
			if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_'
					|| c == '.' || c == '~') {
				sb.append((char) c);
			} else {
				sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
						.append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
			}
		}
		return sb.toString();
	}

	private static byte[] sha256(String s) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] hmac(byte[] key, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	// ---------- answers ----------

	private static Document parse(byte[] xml) throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		f.setExpandEntityReferences(false);
		f.setXIncludeAware(false);
		javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
		builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
			@Override
			public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
				throw e; // no output on stderr: the caller reports it
			}
		});
		return builder.parse(new ByteArrayInputStream(xml));
	}

	static Listing parseListing(byte[] xml, String prefix, String authority) throws IOException {
		Document doc;
		try {
			doc = parse(xml);
		} catch (Exception e) {
			throw new IOException(authority + " did not answer with an S3 listing (is the endpoint an S3 service?)");
		}
		Element root = doc.getDocumentElement();
		if (!"ListBucketResult".equals(root.getNodeName())) {
			throw new IOException(authority + " did not answer with an S3 listing (is the endpoint an S3 service?)");
		}
		Listing out = new Listing();
		for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (!(n instanceof Element)) {
				continue;
			}
			Element e = (Element) n;
			switch (e.getNodeName()) {
			case "IsTruncated":
				out.truncated = "true".equalsIgnoreCase(e.getTextContent().trim());
				break;
			case "NextContinuationToken":
				out.nextToken = e.getTextContent();
				break;
			case "CommonPrefixes":
				String p = child(e, "Prefix");
				if (p != null && !p.isEmpty()) {
					out.folders.add(p);
				}
				break;
			case "Contents":
				String key = child(e, "Key");
				if (key == null || key.equals(prefix)) { // some consoles create an empty object that stands for the folder
					break;
				}
				long size = 0;
				try {
					size = Long.parseLong(child(e, "Size").trim());
				} catch (RuntimeException ignored) {
					// size stays 0
				}
				String modified = child(e, "LastModified");
				out.files.add(new FileEntry(key, key.startsWith(prefix) ? key.substring(prefix.length()) : key, size,
						modified == null ? "" : modified.replace('T', ' ').replaceAll("\\.\\d+Z$|Z$", "")));
				break;
			default:
				break;
			}
		}
		if (!out.truncated) {
			out.nextToken = null;
		}
		return out;
	}

	private static String child(Element parent, String name) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element && n.getNodeName().equals(name)) {
				return n.getTextContent();
			}
		}
		return null;
	}

	private static S3Exception error(int status, byte[] body, String authority) {
		String code = "";
		String message = "";
		try {
			Element root = parse(body).getDocumentElement();
			if ("Error".equals(root.getNodeName())) {
				code = orEmpty(child(root, "Code"));
				message = orEmpty(child(root, "Message"));
			}
		} catch (Exception notXml) {
			// not an S3 error document
		}
		if (code.isEmpty()) {
			return new S3Exception(status, "", authority + " answered HTTP " + status + " without an S3 error");
		}
		return new S3Exception(status, code, "S3 answered HTTP " + status + " " + code
				+ (message.isEmpty() ? "" : ": " + message));
	}

	private static String orEmpty(String s) {
		return s == null ? "" : s.trim();
	}
}
