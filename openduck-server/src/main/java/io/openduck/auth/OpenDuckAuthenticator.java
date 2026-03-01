package io.openduck.auth;

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallStatus; // Add this import
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;

public class OpenDuckAuthenticator implements CallHeaderAuthenticator {

    @Override
    public AuthResult authenticate(CallHeaders headers) {
        String authHeader = headers.get("Authorization");

        if (authHeader == null) {
        	throw CallStatus.UNAUTHENTICATED.withDescription("No Authorization header provided").toRuntimeException();
        }

        // Logic for both Token and User/Pass
        if (authHeader.startsWith("Basic ")) {
            return validateBasic(authHeader.substring(6));
        } else if (authHeader.startsWith("Bearer ")) {
            return validateToken(authHeader.substring(7));
        }

        throw CallStatus.UNAUTHENTICATED.withDescription("Invalid Scheme").toRuntimeException();
    }

    private AuthResult validateBasic(String encoded) {
        // Decode and verify credentials...
        return () -> "admin";
    }

    private AuthResult validateToken(String token) {
        // Verify JWT or opaque token...
        return () -> "token_user";
    }
}