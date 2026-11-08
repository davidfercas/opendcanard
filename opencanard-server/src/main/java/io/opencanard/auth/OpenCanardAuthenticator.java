package io.opencanard.auth;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBConnection;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import io.opencanard.user.User;
import io.opencanard.user.UserRepository;

/**
 * Checks {@code Authorization: Basic} credentials of an Arrow Flight call against {@code opencanard_users}
 * (BCrypt). It sits behind {@link FlightAuthenticator}, which handles the Bearer tokens; any header it cannot
 * check is refused with {@code UNAUTHENTICATED}, never with an internal error.
 */
public class OpenCanardAuthenticator implements CallHeaderAuthenticator {

	private static final Logger logger = LogManager.getLogger(OpenCanardAuthenticator.class);

	private static final String BASIC = "Basic ";
	private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

	private final Connection metadatadb;

	public OpenCanardAuthenticator(Connection metadatadb) {
		this.metadatadb = metadatadb;
	}

	@Override
	public AuthResult authenticate(CallHeaders headers) {
		String authHeader = headers.get("Authorization");
		if (authHeader == null) {
			throw unauthenticated("No Authorization header provided");
		}
		if (authHeader.startsWith("Bearer ")) {
			// Issued tokens and JWTs are recognised by FlightAuthenticator before this class is asked
			throw unauthenticated("Unknown or expired bearer token, sign in again");
		}
		if (!authHeader.startsWith(BASIC)) {
			throw unauthenticated("Unsupported authorization scheme, use Basic or Bearer");
		}

		String username;
		String password;
		try {
			String credentials = new String(Base64.getDecoder().decode(authHeader.substring(BASIC.length()).trim()),
					StandardCharsets.UTF_8);
			int colon = credentials.indexOf(':');
			if (colon < 0) {
				throw unauthenticated("Malformed Basic credentials");
			}
			username = credentials.substring(0, colon);
			password = credentials.substring(colon + 1);
		} catch (IllegalArgumentException e) {
			throw unauthenticated("Malformed Basic credentials");
		}

		User user;
		try (Connection c = ((DuckDBConnection) metadatadb).duplicate()) {
			user = new UserRepository().getUser(c, username);
		} catch (FlightRuntimeException e) {
			throw e;
		} catch (Exception e) {
			logger.error("[flight-auth] cannot look up user '{}'", username, e);
			throw unauthenticated("Internal error during login");
		}
		// same answer for an unknown user and a wrong password
		if (user == null || password.isEmpty() || !ENCODER.matches(password, user.getPasswordHash())) {
			throw unauthenticated("Login failure: Wrong user/password");
		}
		return () -> username;
	}

	private static FlightRuntimeException unauthenticated(String message) {
		return CallStatus.UNAUTHENTICATED.withDescription(message).toRuntimeException();
	}
}
