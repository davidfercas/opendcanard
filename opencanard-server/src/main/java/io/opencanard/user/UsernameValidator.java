package io.opencanard.user;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The login name of a user is also the name of the user's DuckDB file, so it has to be safe as a file
 * name: letters, digits, '_' and '-' only (no path separators, no dots), at most 64 characters, starting
 * with a letter or digit, and not a Windows device name (CON, NUL, COM1...).
 * <p>
 * Pure JDK on purpose, so it can be tested without the rest of the server.
 */
public final class UsernameValidator {

	public static final String RULES = "Username must be 1-64 characters: letters, digits, '_' or '-', "
			+ "starting with a letter or digit";

	private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
	private static final Pattern WINDOWS_RESERVED = Pattern.compile("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$",
			Pattern.CASE_INSENSITIVE);

	private UsernameValidator() {
	}

	/** @throws IllegalArgumentException if the name cannot be used as a database file name */
	public static void validate(String username) {
		if (username == null || !VALID.matcher(username).matches()) {
			throw new IllegalArgumentException(RULES);
		}
		if (WINDOWS_RESERVED.matcher(username).matches()) {
			throw new IllegalArgumentException("'" + username + "' is a reserved name");
		}
	}

	public static boolean isValid(String username) {
		try {
			validate(username);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * The database file of a user: {@code <dir>/<username>.duckdb}.
	 *
	 * @throws IllegalArgumentException if the name is not valid or the file would end up outside {@code dir}
	 */
	public static Path databaseFile(Path dir, String username) {
		validate(username);
		Path base = dir.toAbsolutePath().normalize();
		Path file = base.resolve(username + ".duckdb").normalize();
		if (!file.startsWith(base)) {
			throw new IllegalArgumentException(RULES);
		}
		return file;
	}
}
