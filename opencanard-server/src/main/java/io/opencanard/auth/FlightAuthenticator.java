package io.opencanard.auth;

import java.sql.Connection;
import java.util.concurrent.TimeUnit;

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.auth2.Auth2Constants;
import org.apache.arrow.flight.auth2.AuthUtilities;
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.GeneratedBearerTokenAuthenticator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBConnection;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.google.common.cache.CacheBuilder;

import io.opencanard.user.UserRepository;

/**
 * Authentication of every call on the Arrow Flight port. Three kinds of {@code Authorization} header are accepted:
 * <ol>
 * <li>{@code Basic <base64(user:password)>}: checked against {@code opencanard_users} by
 * {@link OpenCanardAuthenticator}. The response carries {@code Authorization: Bearer <token>}, a random token the
 * client may send instead from then on (kept in memory, forgotten after an hour without use or on restart);</li>
 * <li>{@code Bearer <that random token>};</li>
 * <li>{@code Bearer <JWT>}: the token of the REST {@code POST /api/login}, verified by {@link JwtTokens} (signature,
 * issuer, subject, expiry). The identity is the token's subject.</li>
 * </ol>
 * Anything else (no header, another scheme, an unknown, forged, unsigned, malformed or expired token) is refused with
 * {@code UNAUTHENTICATED}. Whatever the scheme, the user must still exist when the call is made: a user deleted after
 * signing in loses access at once, also through tokens issued before.
 */
public class FlightAuthenticator implements CallHeaderAuthenticator {

	private static final Logger logger = LogManager.getLogger(FlightAuthenticator.class);

	private final Connection metadatadb;
	private final CallHeaderAuthenticator basicAndIssuedTokens;
	private final UserRepository users = new UserRepository();

	public FlightAuthenticator(Connection metadatadb) {
		this.metadatadb = metadatadb;
		this.basicAndIssuedTokens = new GeneratedBearerTokenAuthenticator(new OpenCanardAuthenticator(metadatadb),
				CacheBuilder.newBuilder().expireAfterAccess(60, TimeUnit.MINUTES).maximumSize(100_000));
	}

	@Override
	public AuthResult authenticate(CallHeaders headers) {
		String bearer = AuthUtilities.getValueFromAuthHeader(headers, Auth2Constants.BEARER_PREFIX);
		if (bearer != null && JwtTokens.looksLikeJwt(bearer)) {
			String login;
			try {
				login = JwtTokens.verify(bearer);
			} catch (JWTVerificationException e) {
				logger.info("[flight-auth] JWT refused: {}", e.getMessage());
				throw CallStatus.UNAUTHENTICATED.withDescription("Invalid or expired token, sign in again")
						.toRuntimeException();
			}
			requireExistingUser(login);
			return () -> login;
		}

		// Basic credentials, or a random token issued after a Basic login; anything else is refused there
		AuthResult result = basicAndIssuedTokens.authenticate(headers);
		requireExistingUser(result.getPeerIdentity());
		return result;
	}

	private void requireExistingUser(String login) {
		boolean exists;
		try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
			exists = login != null && users.getUser(c, login) != null;
		} catch (Exception e) {
			logger.error("[flight-auth] cannot look up user '{}'", login, e);
			throw CallStatus.UNAUTHENTICATED.withDescription("Internal error during login").toRuntimeException();
		}
		if (!exists) {
			throw CallStatus.UNAUTHENTICATED.withDescription("The user of this token no longer exists")
					.toRuntimeException();
		}
	}
}
