package io.openduck.auth;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallStatus; // Add this import
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import io.openduck.user.User;
import io.openduck.user.UserRepository;

public class OpenDuckAuthenticator implements CallHeaderAuthenticator {



	private static final CallStatus UNAUTHENTICATED =
		    CallStatus.UNAUTHENTICATED.withDescription("Login failure: Wrong user/password");
	private static final CallStatus AUTHENTICATIONERROR =
		    CallStatus.UNAUTHENTICATED.withDescription("Internal error during login");
	
	private Connection conn;
	
	public OpenDuckAuthenticator(Connection metadatadb) {
		super();
		this.conn = metadatadb;
	}

	@Override
	public AuthResult authenticate(CallHeaders headers) {
		String authHeader = headers.get("Authorization");

		if (authHeader == null) {
			throw CallStatus.UNAUTHENTICATED.withDescription("No Authorization header provided").toRuntimeException();
		}

		// Logic for both Token and User/Pass
		if (authHeader.startsWith("Basic ")) {

			String base64Credentials = authHeader.substring(6);
			String credentials = new String(Base64.getDecoder().decode(base64Credentials));
			String[] values = credentials.split(":", 2);
			String username = values[0];
			String password = values[1];

			try {
				return validateBasic(username, password);
			} catch (Exception e) {
				throw CallStatus.UNAUTHENTICATED.withDescription(e.getMessage()).toRuntimeException();
			}
		} else if (authHeader.startsWith("Bearer ")) {
			return validateToken(authHeader.substring(7));
		}

		throw CallStatus.UNAUTHENTICATED.withDescription("Invalid Scheme").toRuntimeException();
	}

	private AuthResult validateBasic(String username, String password) throws Exception {
		// Decode and verify credentials...
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

		UserRepository userRepository = new UserRepository();
		
		User user = userRepository.getUser(this.conn, username);
			if (user == null || password == null) {
				throw UNAUTHENTICATED.toRuntimeException();
			}
			if (encoder.matches(password, user.getPasswordHash())) {
				return () -> username;
			}
			throw UNAUTHENTICATED.toRuntimeException();
		
	}

	private AuthResult validateToken(String token) {
		// Verify JWT or opaque token...
		return () -> "token_user";
	}
}