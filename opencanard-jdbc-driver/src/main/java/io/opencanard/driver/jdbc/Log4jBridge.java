package io.opencanard.driver.jdbc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The only place where the driver's diagnostics touch log4j. It is a separate class on purpose:
 * {@link DriverDiagnostics} calls it inside a try/catch, so if log4j is missing or broken in the host
 * application (a failure to load this class is a {@link LinkageError}) the diagnostics keep working.
 * <p>
 * Configure the logger {@code io.opencanard.driver.jdbc} at DEBUG in the host's log4j configuration to
 * see the driver's debug lines there.
 */
final class Log4jBridge {

	private static final Logger LOG = LogManager.getLogger("io.opencanard.driver.jdbc");

	private Log4jBridge() {
	}

	static void debug(String message, Throwable t) {
		if (LOG.isDebugEnabled()) {
			if (t == null) {
				LOG.debug(message);
			} else {
				LOG.debug(message, t);
			}
		}
	}

	static void error(String message, Throwable t) {
		if (t == null) {
			LOG.error(message);
		} else {
			LOG.error(message, t);
		}
	}
}
