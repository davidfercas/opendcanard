package io.opencanard.user;

/**
 * The operation is refused to protect the system: deleting the built-in admin user or your own account, taking the
 * {@code admin} role away from them, or deleting or renaming a built-in role.
 */
public class NotAllowedException extends Exception {

	private static final long serialVersionUID = 1L;

	public NotAllowedException(String message) {
		super(message);
	}
}
