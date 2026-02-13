package io.openduck.auth;

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallInfo;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightServerMiddleware;
import org.apache.arrow.flight.RequestContext;


public class AuthMiddlewareFactory implements FlightServerMiddleware.Factory<AuthMiddleware> {

    private final String expectedToken;

    public AuthMiddlewareFactory(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public AuthMiddleware onCallStarted(
            CallInfo callInfo,
            CallHeaders headers,
            RequestContext context) {

        String auth = headers.get("Authorization");

        if (auth == null) {
            throw CallStatus.UNAUTHENTICATED.withDescription("Missing token").toRuntimeException();
        }
        else if (!auth.equals("Bearer " + expectedToken)) {
        	throw CallStatus.UNAUTHENTICATED.withDescription("Invalid token").toRuntimeException();
        }

        return new AuthMiddleware(expectedToken);
    }
}
