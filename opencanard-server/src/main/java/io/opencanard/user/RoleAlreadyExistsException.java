package io.opencanard.user;

/** A role with that name already exists. */
public class RoleAlreadyExistsException extends Exception {

	private static final long serialVersionUID = 1L;

	public RoleAlreadyExistsException(String message) {
		super(message);
	}
}
