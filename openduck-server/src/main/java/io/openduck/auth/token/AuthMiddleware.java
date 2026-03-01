package io.openduck.auth.token;

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightServerMiddleware;



public class AuthMiddleware implements FlightServerMiddleware {

    private final String expectedToken;

    public AuthMiddleware(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public void onBeforeSendingHeaders(CallHeaders outgoingHeaders) {}

    @Override
    public void onCallCompleted(CallStatus status) {}

    @Override
    public void onCallErrored(Throwable err) {}
}
