package io.opencanard.flightsql;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Tells Arrow Flight SQL requests apart from OpenCanard's own Flight protocol, so the server can speak both on
 * the same port.
 * <p>
 * OpenCanard's own clients (its JDBC driver, the DBeaver plugin, plain PyArrow Flight code) put the SQL text in
 * the descriptor command and in the ticket. Flight SQL clients (ADBC, the Apache Arrow Flight SQL JDBC driver,
 * ODBC) put a protobuf {@code Any} there whose type URL names a message of the
 * {@code arrow.flight.protocol.sql} package. SQL text never parses as such a message: its first byte would have
 * to be the field tag {@code 0x0A} (a newline) followed by a type URL starting with that package name.
 */
public final class FlightSqlCommands {

	/** Type URL prefix of every Flight SQL command, ticket and action body. */
	static final String TYPE_URL_PREFIX = "type.googleapis.com/arrow.flight.protocol.sql.";

	private FlightSqlCommands() {
	}

	/** True when {@code bytes} (a descriptor command, ticket or action body) is a Flight SQL message. */
	public static boolean isFlightSql(byte[] bytes) {
		if (bytes == null || bytes.length < TYPE_URL_PREFIX.length() + 2 || bytes[0] != 0x0A) {
			return false;
		}
		try {
			return Any.parseFrom(bytes).getTypeUrl().startsWith(TYPE_URL_PREFIX);
		} catch (InvalidProtocolBufferException e) {
			return false;
		}
	}
}
