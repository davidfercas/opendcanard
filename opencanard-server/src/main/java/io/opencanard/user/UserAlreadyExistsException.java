package io.opencanard.user;

/** The user, or the database file reserved for that user, already exists. */
public class UserAlreadyExistsException extends Exception {

	private static final long serialVersionUID = 1L;

	public UserAlreadyExistsException(String message) {
		super(message);
	}
}
