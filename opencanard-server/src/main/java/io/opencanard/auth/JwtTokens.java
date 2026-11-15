package io.opencanard.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import io.opencanard.user.User;

/**
 * The JSON Web Tokens of OpenCanard: issued by the REST {@code POST /api/login} and accepted as
 * {@code Authorization: Bearer <jwt>} by the REST API, the web console and the Arrow Flight port.
 * <p>
 * A token is signed with HMAC-SHA256 and carries {@code iss = "opencanard"}, {@code sub} (the login), {@code iat}
 * and {@code exp} (one hour). {@link #verify(String)} accepts a token only if all of them are present and valid:
 * a token signed with another key, with {@code alg: none}, without expiry, expired, from another issuer or without
 * subject is refused. Whether the user still exists is up to the caller.
 * <p>
 * The signing key is set once at start-up with {@link #configure}; until then every call fails, so the server never
 * signs or accepts tokens with a default key. {@link #loadSecret} picks the key, in this order: the environment
 * variable {@code OPENCANARD_JWT_SECRET}; the property {@code jwt.secret}; the file {@code metadata/jwt.key}, created
 * with 256 random bits and owner-only permissions on the first start. A configured secret shorter than
 * {@link #MIN_SECRET_BYTES} bytes is refused.
 */
public final class JwtTokens {

	public static final String ISSUER = "opencanard";
	/** Default token lifetime, in minutes, when {@code jwt.expiry.minutes} is not set. */
	public static final int DEFAULT_LIFETIME_MINUTES = 60;
	/** HMAC-SHA256 wants a key at least as long as its output: 32 bytes. */
	public static final int MIN_SECRET_BYTES = 32;

	private static volatile Algorithm algorithm;
	private static volatile JWTVerifier verifier;
	private static volatile long lifetimeMs = DEFAULT_LIFETIME_MINUTES * 60_000L;

	private JwtTokens() {
	}

	/**
	 * Sets the signing key and the token lifetime. Called once when the server starts; calling it again (tests)
	 * replaces both, which invalidates every token issued before.
	 *
	 * @throws IllegalArgumentException if the secret is shorter than {@link #MIN_SECRET_BYTES} or the lifetime is not
	 *                                  positive
	 */
	public static synchronized void configure(byte[] secret, int lifetimeMinutes) {
		if (secret == null || secret.length < MIN_SECRET_BYTES) {
			throw new IllegalArgumentException("The JWT secret must be at least " + MIN_SECRET_BYTES + " bytes long");
		}
		if (lifetimeMinutes <= 0) {
			throw new IllegalArgumentException("jwt.expiry.minutes must be greater than 0");
		}
		Algorithm alg = Algorithm.HMAC256(secret);
		verifier = JWT.require(alg) //
				.withIssuer(ISSUER) //
				.withClaimPresence("sub") //
				.withClaimPresence("exp") //
				.build();
		lifetimeMs = lifetimeMinutes * 60_000L;
		algorithm = alg;
	}

	/**
	 * The signing key to pass to {@link #configure}: {@code configured} (the environment variable or property) when
	 * set, otherwise the content of {@code keyFile}, which is created with a random key if it does not exist.
	 *
	 * @throws IllegalArgumentException if {@code configured} is set but shorter than {@link #MIN_SECRET_BYTES} bytes
	 */
	public static byte[] loadSecret(String configured, Path keyFile) throws IOException {
		if (configured != null && !configured.isBlank()) {
			byte[] secret = configured.getBytes(StandardCharsets.UTF_8);
			if (secret.length < MIN_SECRET_BYTES) {
				throw new IllegalArgumentException("The configured JWT secret (OPENCANARD_JWT_SECRET or jwt.secret) "
						+ "must be at least " + MIN_SECRET_BYTES + " bytes long");
			}
			return secret;
		}
		if (!Files.exists(keyFile)) {
			byte[] fresh = new byte[32];
			new SecureRandom().nextBytes(fresh);
			try {
				Files.write(keyFile, Base64.getEncoder().encode(fresh), StandardOpenOption.CREATE_NEW);
				try {
					Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
				} catch (UnsupportedOperationException ignored) {
					// not a POSIX file system (Windows)
				}
			} catch (FileAlreadyExistsException raced) {
				// another start created it first: use that one
			}
		}
		byte[] stored = Files.readAllBytes(keyFile);
		if (stored.length < MIN_SECRET_BYTES) {
			throw new IllegalArgumentException("The JWT key file " + keyFile + " is shorter than " + MIN_SECRET_BYTES
					+ " bytes: delete it to have a new one created, or set OPENCANARD_JWT_SECRET");
		}
		return stored;
	}

	/** The lifetime of new tokens, in milliseconds. */
	public static long lifetimeMs() {
		return lifetimeMs;
	}

	private static Algorithm algorithm() {
		Algorithm alg = algorithm;
		if (alg == null) {
			throw new IllegalStateException("JwtTokens.configure was not called: no JWT signing key");
		}
		return alg;
	}

	/** A new token for the user, valid for {@link #lifetimeMs()}. */
	public static String issue(User user) {
		Algorithm alg = algorithm();
		long now = System.currentTimeMillis();
		return JWT.create() //
				.withIssuer(ISSUER) //
				.withSubject(user.getUsername()) //
				// kept for clients that read it; the server only trusts the subject, and reads roles from the database
				.withClaim("username", user.getUsername()) //
				.withArrayClaim("roles", user.getRoles().toArray(new String[0])) //
				.withIssuedAt(new Date(now)) //
				.withExpiresAt(new Date(now + lifetimeMs)) //
				.sign(alg);
	}

	/**
	 * Checks signature, issuer, subject and expiry.
	 *
	 * @return the login the token was issued to
	 * @throws JWTVerificationException if the token is malformed, forged, unsigned, expired or incomplete
	 */
	public static String verify(String token) throws JWTVerificationException {
		if (token == null || token.isEmpty()) {
			throw new JWTVerificationException("Empty token");
		}
		JWTVerifier v = verifier;
		if (v == null) {
			throw new JWTVerificationException("No JWT signing key configured");
		}
		DecodedJWT decoded = v.verify(token);
		String subject = decoded.getSubject();
		if (subject == null || subject.isEmpty()) {
			throw new JWTVerificationException("The token has no subject");
		}
		return subject;
	}

	/**
	 * Whether a bearer value has the shape of a JWT (three dot-separated parts). The random tokens the Flight server
	 * hands out after a Basic login never contain a dot.
	 */
	public static boolean looksLikeJwt(String token) {
		if (token == null) {
			return false;
		}
		int first = token.indexOf('.');
		int second = first < 0 ? -1 : token.indexOf('.', first + 1);
		return second > 0 && token.indexOf('.', second + 1) < 0;
	}
}
