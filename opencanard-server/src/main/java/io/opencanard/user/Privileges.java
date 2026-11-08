package io.opencanard.user;

import java.util.List;

/**
 * The catalog of privileges an admin can grant to a user or to a role. A user has a privilege when it was granted
 * to the user directly or to any of the user's roles (see {@link PrivilegeRepository#effective}).
 * <p>
 * New privileges are added to {@link #ALL}; the tables store them by name, so nothing else has to change for the
 * administration endpoints and the console to offer them.
 */
public final class Privileges {

	/** One grantable privilege. */
	public static final class Privilege {
		private final String name;
		private final String description;

		public Privilege(String name, String description) {
			this.name = name;
			this.description = description;
		}

		public String getName() {
			return name;
		}

		public String getDescription() {
			return description;
		}
	}

	/**
	 * The user's database is opened with {@code access_mode=READ_ONLY}: queries can read but never change it (nor
	 * any database attached from it). Not applied to admins, who work on the metadata database.
	 */
	public static final String READ_ONLY = "READ_ONLY";

	public static final List<Privilege> ALL = List.of(new Privilege(READ_ONLY,
			"Read-only access: the user's database is opened with access_mode=READ_ONLY, so queries cannot change it. "
					+ "Not applied to admins, who work on the metadata database."));

	private Privileges() {
	}

	public static boolean exists(String name) {
		return find(name) != null;
	}

	/** The privilege with that name (case-insensitive), or {@code null}. */
	public static Privilege find(String name) {
		if (name == null) {
			return null;
		}
		for (Privilege p : ALL) {
			if (p.getName().equalsIgnoreCase(name.trim())) {
				return p;
			}
		}
		return null;
	}
}
