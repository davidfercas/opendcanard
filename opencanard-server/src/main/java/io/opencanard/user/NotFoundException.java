package io.opencanard.user;

/** The user or role named in the request does not exist. */
public class NotFoundException extends Exception {

	private static final long serialVersionUID = 1L;

	public NotFoundException(String message) {
		super(message);
	}
}
