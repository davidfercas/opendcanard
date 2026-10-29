package io.opencanard.driver.jdbc;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Debug logging and failure diagnostics of the JDBC driver.
 * <p>
 * It depends on the JDK only (log4j is reached through {@link Log4jBridge}, inside a try/catch), so it
 * keeps working inside a host application such as a data virtualization server where the log4j
 * configuration is not under our control and where a conflicting library can make the driver fail to
 * start.
 *
 * <h3>Switching debug output on</h3> Any of:
 * <ul>
 * <li>JVM option {@code -Dopencanard.jdbc.debug=true}</li>
 * <li>environment variable {@code OPENCANARD_JDBC_DEBUG=true}</li>
 * <li>connection property {@code debug=true}</li>
 * <li>{@code debug=true} in the URL query, e.g. {@code jdbc:opencanard://host:8815/?debug=true}</li>
 * </ul>
 * Once on, it stays on for the JVM. The output goes to stderr and is appended to
 * {@code <java.io.tmpdir>/opencanard-jdbc-debug.log}, or to the file given in
 * {@code -Dopencanard.jdbc.debug.file=<path>}. Passwords are never written; SQL text is, so do not leave
 * it on in production.
 *
 * <h3>Failures are always reported</h3> A failed connection prints its full cause chain, the location of
 * the libraries the driver depends on, and the first failure seen in this JVM to stderr and to log4j,
 * and puts the root cause chain in the message of the {@link SQLException} it throws. That last part
 * matters because after a static initializer fails once, the JVM only reports "Could not initialize
 * class X" for every later attempt: the first failure is the one that has the real cause, so it is kept.
 */
final class DriverDiagnostics {

	static final String DEBUG_PROPERTY = "opencanard.jdbc.debug";
	static final String DEBUG_ENV = "OPENCANARD_JDBC_DEBUG";
	static final String DEBUG_FILE_PROPERTY = "opencanard.jdbc.debug.file";
	static final String DEFAULT_FILE_NAME = "opencanard-jdbc-debug.log";

	private static final Pattern URL_DEBUG = Pattern.compile("(?i)[?&;]debug=(true|1|yes|on)\\b");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
	private static final int MAX_CHAIN = 8;
	private static final int MAX_MESSAGE = 300;
	private static final int MAX_SQL = 500;

	/** Libraries whose origin we want to see: a copy from the host application can win over ours. */
	private static final String[] PROBE_CLASSES = { "io.grpc.netty.NettyChannelBuilder", "io.grpc.ManagedChannel",
			"io.netty.channel.Channel", "io.netty.buffer.PooledByteBufAllocator", "com.google.common.base.Preconditions",
			"com.google.protobuf.Message", "org.apache.arrow.flight.FlightClient",
			"org.apache.arrow.memory.RootAllocator", "org.apache.logging.log4j.LogManager",
			"org.slf4j.LoggerFactory" };

	private static volatile boolean enabled = initiallyEnabled();
	private static boolean announced;
	private static boolean environmentLogged;
	private static Throwable firstFailure;
	private static String firstFailureTime;

	private DriverDiagnostics() {
	}

	// ---------------------------------------------------------------- switch

	private static boolean initiallyEnabled() {
		try {
			return truthy(System.getProperty(DEBUG_PROPERTY)) || truthy(System.getenv(DEBUG_ENV));
		} catch (Throwable t) { // for example a SecurityManager
			return false;
		}
	}

	static boolean truthy(String value) {
		if (value == null) {
			return false;
		}
		String v = value.trim().toLowerCase();
		return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
	}

	/** Called for every connection: turns debug on when the URL or the properties ask for it. */
	static void configure(String url, Properties props) {
		boolean requested = (props != null && truthy(props.getProperty("debug")))
				|| (url != null && URL_DEBUG.matcher(url).find());
		if (requested) {
			enabled = true;
		}
		if (enabled) {
			announce();
		}
	}

	static boolean isEnabled() {
		return enabled;
	}

	private static synchronized void announce() {
		if (announced) {
			return;
		}
		announced = true;
		emit("INFO", "OpenCanard JDBC debug output is ON (stderr and " + debugFile() + ")", null, false);
	}

	// ---------------------------------------------------------------- logging

	/** Debug line. Written when debug is on; always offered to log4j, which decides by its own level. */
	static void debug(String pattern, Object... args) {
		String message = format(pattern, args);
		toLog4j(false, message, null);
		emit("DEBUG", message, null, false);
	}

	/** A failure that is only interesting while debugging (it is also reported by the caller). */
	static void debugError(String message, Throwable t) {
		toLog4j(false, message, t);
		emit("DEBUG", message, t, false);
	}

	/** An error that is always reported (stderr and log4j), and written to the file when debug is on. */
	static void error(String message, Throwable t) {
		toLog4j(true, message, t);
		emit("ERROR", message, t, true);
	}

	private static void toLog4j(boolean error, String message, Throwable t) {
		try {
			if (error) {
				Log4jBridge.error(message, t);
			} else {
				Log4jBridge.debug(message, t);
			}
		} catch (Throwable ignored) {
			// log4j is missing or broken in the host application: stderr and the file still work
		}
	}

	private static synchronized void emit(String level, String message, Throwable t, boolean force) {
		if (!force && !enabled) {
			return;
		}
		StringBuilder line = new StringBuilder();
		line.append(LocalDateTime.now().format(TIME)).append(" [").append(Thread.currentThread().getName())
				.append("] ").append(level).append(" OpenCanard-JDBC - ").append(message);
		if (t != null) {
			line.append(System.lineSeparator()).append(stackTrace(t));
		}
		System.err.println(line);
		if (enabled) {
			appendToFile(line.toString());
		}
	}

	private static Path debugFile() {
		String configured = null;
		try {
			configured = System.getProperty(DEBUG_FILE_PROPERTY);
		} catch (Throwable ignored) {
		}
		if (configured != null && !configured.isEmpty()) {
			return Paths.get(configured);
		}
		return Paths.get(System.getProperty("java.io.tmpdir", "."), DEFAULT_FILE_NAME);
	}

	private static void appendToFile(String text) {
		try {
			Path file = debugFile().toAbsolutePath();
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			Files.write(file, (text + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Throwable ignored) {
			// never let diagnostics break a connection
		}
	}

	// ---------------------------------------------------------------- environment

	/** Java, class loader and library origins, once per JVM, when debug is on. */
	static void logEnvironmentOnce() {
		if (!enabled) {
			return;
		}
		synchronized (DriverDiagnostics.class) {
			if (environmentLogged) {
				return;
			}
			environmentLogged = true;
		}
		emit("DEBUG", environmentReport(), null, false);
	}

	static String environmentReport() {
		StringBuilder sb = new StringBuilder("Environment:");
		String nl = System.lineSeparator();
		sb.append(nl).append("  java      : ").append(System.getProperty("java.version")).append(" (")
				.append(System.getProperty("java.vendor")).append("), home ").append(System.getProperty("java.home"));
		sb.append(nl).append("  os        : ").append(System.getProperty("os.name")).append(' ')
				.append(System.getProperty("os.arch"));
		sb.append(nl).append("  user.dir  : ").append(System.getProperty("user.dir"));
		sb.append(nl).append("  driver jar: ").append(locationOf(DriverDiagnostics.class));
		sb.append(nl).append("  loaders   : ").append(loaderChain(DriverDiagnostics.class.getClassLoader()));
		try {
			sb.append(nl).append("  JVM args  : ").append(ManagementFactory.getRuntimeMXBean().getInputArguments());
		} catch (Throwable t) {
			sb.append(nl).append("  JVM args  : (not available: ").append(t.getClass().getSimpleName()).append(')');
		}
		sb.append(nl).append("  Libraries the driver uses, and where each one is loaded from");
		sb.append(nl).append("  (not the driver jar means the host application's copy is used instead of ours):");
		for (String name : PROBE_CLASSES) {
			sb.append(nl).append("    ").append(describeClass(name));
		}
		return sb.toString();
	}

	/** Where a class is loaded from, without initializing it, so this cannot fail the way the driver can. */
	static String describeClass(String name) {
		try {
			Class<?> c = Class.forName(name, false, DriverDiagnostics.class.getClassLoader());
			return name + " <- " + locationOf(c);
		} catch (Throwable t) {
			return name + " <- NOT LOADABLE: " + summarize(t);
		}
	}

	static String locationOf(Class<?> c) {
		try {
			CodeSource source = c.getProtectionDomain().getCodeSource();
			return source == null || source.getLocation() == null ? "(JDK / no code source)"
					: String.valueOf(source.getLocation());
		} catch (Throwable t) {
			return "(unknown: " + t.getClass().getSimpleName() + ")";
		}
	}

	private static String loaderChain(ClassLoader loader) {
		StringBuilder sb = new StringBuilder();
		int depth = 0;
		while (loader != null && depth++ < 6) {
			if (sb.length() > 0) {
				sb.append(" -> ");
			}
			sb.append(loader.getClass().getName());
			loader = loader.getParent();
		}
		return sb.length() == 0 ? "(bootstrap)" : sb.toString();
	}

	// ---------------------------------------------------------------- failures

	/**
	 * Reports a failure to create a connection and returns the exception to throw. Its message carries the
	 * root cause chain, so it is visible even where only the message of the exception is shown.
	 */
	static SQLException connectFailure(Throwable t, String host, int port) {
		rememberFirstFailure(t);

		String target = host + ":" + port;
		error("Cannot create the OpenCanard connection to " + target, t);

		Throwable first;
		String firstTime;
		synchronized (DriverDiagnostics.class) {
			first = firstFailure;
			firstTime = firstFailureTime;
		}
		if (first != null && first != t) {
			error("First failure seen in this JVM, at " + firstTime
					+ " (later attempts only report 'Could not initialize class')", first);
		}
		emit("ERROR", environmentReport(), null, true);
		toLog4j(true, environmentReport(), null);

		StringBuilder message = new StringBuilder("Cannot create the OpenCanard connection to ").append(target)
				.append(": ").append(summarizeChain(t));
		if (first != null && first != t) {
			message.append(" | first failure in this JVM (").append(firstTime).append("): ")
					.append(summarizeChain(first));
		}
		return new SQLException(message.toString(), t);
	}

	/** Keeps the first linkage / initialization failure: that is the one with the real cause. */
	private static synchronized void rememberFirstFailure(Throwable t) {
		if (firstFailure == null && isLinkageProblem(t) && !isAlreadyFailedInitialization(t)) {
			firstFailure = t;
			firstFailureTime = LocalDateTime.now().format(TIME);
		}
	}

	private static boolean isLinkageProblem(Throwable t) {
		int depth = 0;
		for (Throwable c = t; c != null && depth++ < MAX_CHAIN; c = c.getCause() == c ? null : c.getCause()) {
			if (c instanceof LinkageError) {
				return true; // includes ExceptionInInitializerError, NoClassDefFoundError, NoSuchMethodError...
			}
		}
		return false;
	}

	/** The JVM's repeat report for a class whose static initializer failed earlier. */
	static boolean isAlreadyFailedInitialization(Throwable t) {
		return t instanceof NoClassDefFoundError && t.getMessage() != null
				&& t.getMessage().startsWith("Could not initialize class");
	}

	// ---------------------------------------------------------------- text helpers

	/** {@code ExceptionInInitializerError: msg <- caused by: NoSuchMethodError: msg ...} */
	static String summarizeChain(Throwable t) {
		StringBuilder sb = new StringBuilder();
		int depth = 0;
		for (Throwable c = t; c != null && depth < MAX_CHAIN; depth++) {
			if (depth > 0) {
				sb.append(" <- caused by: ");
			}
			sb.append(summarize(c));
			Throwable next = c.getCause();
			c = next == c ? null : next;
		}
		return sb.toString();
	}

	static String summarize(Throwable t) {
		String message = t.getMessage();
		return t.getClass().getName() + (message == null ? "" : ": " + abbreviate(message, MAX_MESSAGE));
	}

	static String abbreviate(String text) {
		return abbreviate(text, MAX_SQL);
	}

	static String abbreviate(String text, int max) {
		if (text == null) {
			return "null";
		}
		String oneLine = text.replaceAll("\\s+", " ").trim();
		return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "... (" + oneLine.length() + " chars)";
	}

	/** Replaces each {} with the next argument. */
	static String format(String pattern, Object... args) {
		if (args == null || args.length == 0) {
			return pattern;
		}
		StringBuilder sb = new StringBuilder();
		int from = 0;
		int arg = 0;
		while (arg < args.length) {
			int at = pattern.indexOf("{}", from);
			if (at < 0) {
				break;
			}
			sb.append(pattern, from, at).append(String.valueOf(args[arg++]));
			from = at + 2;
		}
		return sb.append(pattern.substring(from)).toString();
	}

	private static String stackTrace(Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		return sw.toString().stripTrailing();
	}
}
