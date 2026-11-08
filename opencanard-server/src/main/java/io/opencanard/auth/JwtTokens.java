package io.opencanard.auth;

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
 */
public final class JwtTokens {

	public static final String ISSUER = "opencanard";
	public static final long LIFETIME_MS = 60L * 60 * 1000;

	/**
	 * Signing key. Still a constant compiled into the server: anyone who reads the code can forge tokens, so moving it
	 * to the configuration is tracked separately (issue #12). Keep it in this one place.
	 */
	private static final String SECRET = "your-super-secret-key-change-this";

	private static final Algorithm ALGORITHM = Algorithm.HMAC256(SECRET);
	private static final JWTVerifier VERIFIER = JWT.require(ALGORITHM) //
			.withIssuer(ISSUER) //
			.withClaimPresence("sub") //
			.withClaimPresence("exp") //
			.build();

	private JwtTokens() {
	}

	/** A new token for the user, valid for {@link #LIFETIME_MS}. */
	public static String issue(User user) {
		long now = System.currentTimeMillis();
		return JWT.create() //
				.withIssuer(ISSUER) //
				.withSubject(user.getUsername()) //
				// kept for clients that read it; the server only trusts the subject, and reads roles from the database
				.withClaim("username", user.getUsername()) //
				.withArrayClaim("roles", user.getRoles().toArray(new String[0])) //
				.withIssuedAt(new Date(now)) //
				.withExpiresAt(new Date(now + LIFETIME_MS)) //
				.sign(ALGORITHM);
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
		DecodedJWT decoded = VERIFIER.verify(token);
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
