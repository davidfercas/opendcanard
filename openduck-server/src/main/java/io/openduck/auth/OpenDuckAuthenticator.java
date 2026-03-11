package io.openduck.auth;

import java.util.Base64;
import java.util.HashMap;

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallStatus; // Add this import
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class OpenDuckAuthenticator implements CallHeaderAuthenticator {

	private HashMap<String, String> users;

	private static final CallStatus UNAUTHENTICATED =
		    CallStatus.UNAUTHENTICATED.withDescription("Login failure: Wrong user/password");
	
	public OpenDuckAuthenticator() {
		super();
		users = new HashMap<String, String>();
		users.put("admin", "$2a$10$mcPJoVjXDaDXsGT/2PZUxOy/ZgWM9AVsXN9Q5uDxsURBBocKr1LXi");
		users.put("user1", "1234");
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

			return validateBasic(username, password);
		} else if (authHeader.startsWith("Bearer ")) {
			return validateToken(authHeader.substring(7));
		}

		throw CallStatus.UNAUTHENTICATED.withDescription("Invalid Scheme").toRuntimeException();
	}

	private AuthResult validateBasic(String user, String password) {
		// Decode and verify credentials...
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

		if (user == null || password == null) {
			throw UNAUTHENTICATED.toRuntimeException();
		}
		if (encoder.matches(password, this.users.get(user))) {
			return () -> user;
		}
		throw UNAUTHENTICATED.toRuntimeException();
	}

	private AuthResult validateToken(String token) {
		// Verify JWT or opaque token...
		return () -> "token_user";
	}
}