package io.opencanard.rest;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.duckdb.DuckDBConnection;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;
import io.opencanard.user.DatabaseRegistry;
import io.opencanard.user.NotAllowedException;
import io.opencanard.user.NotFoundException;
import io.opencanard.user.PrivilegeRepository;
import io.opencanard.user.PrivilegeService;
import io.opencanard.user.Privileges;
import io.opencanard.user.RoleAlreadyExistsException;
import io.opencanard.user.RoleRepository;
import io.opencanard.user.RoleService;
import io.opencanard.user.User;
import io.opencanard.user.UserAlreadyExistsException;
import io.opencanard.user.UserService;

/**
 * The administration endpoints behind the console's Administration menu: users ({@code /management/users}), roles
 * ({@code /management/roles}) and the privileges granted to them ({@code /management/privileges},
 * {@code .../{name}/privileges/{privilege}}). Every one of them requires the role {@code admin}, read from the users tables on each
 * call by {@link AuthMiddleware}.
 * <p>
 * Errors: {@code 400} invalid input or a protected operation (deleting the built-in admin user or your own account,
 * taking the admin role away from them, deleting or renaming a built-in role), {@code 403} not an admin,
 * {@code 404} unknown user or role, {@code 409} already exists. The body is {@code {"title": "..."}} (Javalin).
 */
public class AdminController {

	private static final Logger logger = LogManager.getLogger(AdminController.class);

	private final Connection metadatadb;
	private final UserService users;
	private final RoleService roles;
	private final PrivilegeService privileges;

	public AdminController(Connection metadatadb, UserService users) {
		this.metadatadb = metadatadb;
		this.users = users;
		this.roles = new RoleService(metadatadb, users);
		this.privileges = new PrivilegeService(metadatadb, users);
	}

	// ---------- users ----------

	@OpenApi(path = "/management/users", methods = HttpMethod.GET, summary = "List users",
			description = "Lists the users with their roles, database files and privileges (never their password "
					+ "hashes). 'privileges' are granted to the user directly, 'effectivePrivileges' also include the "
					+ "ones of its roles. 'protected' marks the built-in admin user, which cannot be deleted. "
					+ "Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			responses = { @OpenApiResponse(status = "200", description = "List of users"),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role") })
	public void listUsers(Context ctx) {
		requireAdmin(ctx);
		List<Map<String, Object>> out = new ArrayList<>();
		try (Connection c = duplicate()) {
			// read the side tables first: a second query on a connection closes its open result
			Map<String, List<String>> databases = new HashMap<>();
			for (DatabaseRegistry.Entry e : new DatabaseRegistry().list(c)) {
				databases.computeIfAbsent(e.getLogin(), k -> new ArrayList<>()).add(e.getDuckdbFile());
			}
			Map<String, List<String>> rolesByUser = new RoleRepository().rolesByUser(c);
			PrivilegeRepository privilegeRepository = new PrivilegeRepository();
			Map<String, List<String>> privilegesByUser = privilegeRepository.byUser(c);
			Map<String, List<String>> privilegesByRole = privilegeRepository.byRole(c);

			try (Statement st = c.createStatement();
					ResultSet rs = st.executeQuery(
							"SELECT username, created_at FROM main.opencanard_users ORDER BY username")) {
				while (rs.next()) {
					String name = rs.getString("username");
					java.sql.Timestamp created = rs.getTimestamp("created_at");
					Map<String, Object> u = new LinkedHashMap<>();
					u.put("username", name);
					u.put("roles", rolesByUser.getOrDefault(name, List.of()));
					u.put("protected", users.isProtected(name));
					u.put("created_at", created != null ? created.toString() : "");
					u.put("databases", databases.getOrDefault(name, List.of()));
					List<String> direct = privilegesByUser.getOrDefault(name, List.of());
					java.util.TreeSet<String> effective = new java.util.TreeSet<>(direct);
					for (String r : rolesByUser.getOrDefault(name, List.of())) {
						effective.addAll(privilegesByRole.getOrDefault(r, List.of()));
					}
					u.put("privileges", direct);
					u.put("effectivePrivileges", new ArrayList<>(effective));
					out.add(u);
				}
			}
			ctx.json(out);
		} catch (SQLException e) {
			logger.error("Failed to fetch users", e);
			ctx.status(500).json(Map.of("title", "Internal Server Error: " + e.getMessage()));
		}
	}

	@OpenApi(path = "/management/users", methods = HttpMethod.POST, summary = "Create a user",
			description = "Creates a user, gives it the roles listed in 'roles' (default: ['user']; a single 'role' "
					+ "string is also accepted) and creates the user's own DuckDB database file, named after the login. "
					+ "The username may only contain letters, digits, '_' and '-' (max. 64 characters). "
					+ "Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			requestBody = @OpenApiRequestBody(required = true, content = @OpenApiContent(from = CreateUserRequest.class)),
			responses = {
					@OpenApiResponse(status = "201", description = "User created",
							content = @OpenApiContent(from = CreateUserResponse.class)),
					@OpenApiResponse(status = "400", description = "Invalid username, password or role"),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role"),
					@OpenApiResponse(status = "409", description = "The user already exists") })
	public void createUser(Context ctx) {
		requireAdmin(ctx);
		Map<String, Object> body = body(ctx);
		String username = string(body, "username");
		String password = string(body, "password");
		List<String> wanted = roleList(body);
		try {
			User user = users.createUser(username, password, wanted);
			logger.info("User '{}' created by '{}'", user.getUsername(), (String) ctx.attribute("username"));
			ctx.status(201).json(Map.of("username", user.getUsername(), "roles", user.getRoles(), "database",
					user.getUsername() + ".duckdb"));
		} catch (IllegalArgumentException e) {
			throw new BadRequestResponse(e.getMessage());
		} catch (UserAlreadyExistsException e) {
			throw new ConflictResponse(e.getMessage());
		} catch (Exception e) {
			logger.error("Failed to create user '{}'", username, e);
			ctx.status(500).json(Map.of("title", "The user could not be created: " + e.getMessage()));
		}
	}

	@OpenApi(path = "/management/users/{username}/roles", methods = HttpMethod.PUT, summary = "Set the roles of a user",
			description = "Replaces the roles of the user with {\"roles\": [...]} (at least one, all existing). The built-in "
					+ "admin user always keeps 'admin', and nobody can take 'admin' away from themselves. A user that "
					+ "stops being an admin gets its personal database. Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			pathParams = { @OpenApiParam(name = "username", required = true) },
			responses = { @OpenApiResponse(status = "200", description = "{username, roles}"),
					@OpenApiResponse(status = "400", description = "Invalid or unknown role, or protected change"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role"),
					@OpenApiResponse(status = "404", description = "The user does not exist") })
	public void setUserRoles(Context ctx) {
		requireAdmin(ctx);
		String username = ctx.pathParam("username");
		List<String> wanted = roleList(body(ctx));
		try {
			User user = users.setRoles(username, wanted, ctx.attribute("username"));
			ctx.json(Map.of("username", user.getUsername(), "roles", user.getRoles()));
		} catch (IllegalArgumentException | NotAllowedException e) {
			throw new BadRequestResponse(e.getMessage());
		} catch (NotFoundException e) {
			throw new NotFoundResponse(e.getMessage());
		} catch (Exception e) {
			logger.error("Failed to set the roles of '{}'", username, e);
			ctx.status(500).json(Map.of("title", "The roles could not be changed: " + e.getMessage()));
		}
	}

	@OpenApi(path = "/management/users/{username}", methods = HttpMethod.DELETE, summary = "Delete a user",
			description = "Deletes the user, its roles, its S3 storages and its registered DuckDB database files, which "
					+ "are removed from the data folder. The built-in admin user and your own account cannot be deleted. "
					+ "Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			pathParams = { @OpenApiParam(name = "username", required = true, description = "Login to delete") },
			responses = { @OpenApiResponse(status = "200", description = "User deleted: {username, removedFiles}"),
					@OpenApiResponse(status = "400", description = "The built-in admin user or your own account"),
					@OpenApiResponse(status = "401", description = "Missing or invalid credentials"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role"),
					@OpenApiResponse(status = "404", description = "The user does not exist"),
					@OpenApiResponse(status = "500", description = "A database file could not be removed; the user is kept") })
	public void deleteUser(Context ctx) {
		requireAdmin(ctx);
		String username = ctx.pathParam("username");
		try {
			List<String> removed = users.deleteUser(username, ctx.attribute("username"));
			ctx.json(Map.of("username", username, "removedFiles", removed));
		} catch (IllegalArgumentException | NotAllowedException e) {
			throw new BadRequestResponse(e.getMessage());
		} catch (NotFoundException e) {
			throw new NotFoundResponse(e.getMessage());
		} catch (Exception e) {
			logger.error("Failed to delete user '{}'", username, e);
			ctx.status(500).json(Map.of("title", "The user could not be deleted: " + e.getMessage()));
		}
	}

	// ---------- roles ----------

	@OpenApi(path = "/management/roles", methods = HttpMethod.GET, summary = "List roles",
			description = "Lists the roles: name, description, number of users that have it, its privileges and whether "
					+ "it is built in (admin, user: they cannot be deleted or renamed). Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			responses = { @OpenApiResponse(status = "200", description = "List of roles"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role") })
	public void listRoles(Context ctx) {
		requireAdmin(ctx);
		try {
			Map<String, List<String>> byRole;
			try (Connection c = duplicate()) {
				byRole = new PrivilegeRepository().byRole(c);
			}
			List<Map<String, Object>> out = new ArrayList<>();
			for (RoleRepository.Role r : roles.list()) {
				Map<String, Object> m = role(r);
				m.put("privileges", byRole.getOrDefault(r.getName(), List.of()));
				out.add(m);
			}
			ctx.json(out);
		} catch (SQLException e) {
			logger.error("Failed to fetch roles", e);
			ctx.status(500).json(Map.of("title", "Internal Server Error: " + e.getMessage()));
		}
	}

	@OpenApi(path = "/management/roles", methods = HttpMethod.POST, summary = "Create a role",
			description = "Creates a role from {\"name\", \"description\"}. The name may contain letters, digits, '_', '-' "
					+ "and '.' (max. 64 characters). Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			responses = { @OpenApiResponse(status = "201", description = "The role"),
					@OpenApiResponse(status = "400", description = "Invalid name or description"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role"),
					@OpenApiResponse(status = "409", description = "The role already exists") })
	public void createRole(Context ctx) {
		requireAdmin(ctx);
		Map<String, Object> body = body(ctx);
		try {
			ctx.status(201).json(role(roles.create(string(body, "name"), string(body, "description"))));
		} catch (IllegalArgumentException e) {
			throw new BadRequestResponse(e.getMessage());
		} catch (RoleAlreadyExistsException e) {
			throw new ConflictResponse(e.getMessage());
		} catch (Exception e) {
			logger.error("Failed to create a role", e);
			ctx.status(500).json(Map.of("title", "The role could not be created: " + e.getMessage()));
		}
	}

	@OpenApi(path = "/management/roles/{name}", methods = HttpMethod.PUT, summary = "Change a role",
			description = "Changes the description and, except for the built-in roles, the name ({\"name\", "
					+ "\"description\"}). Renaming keeps the role on every user that has it. Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			pathParams = { @OpenApiParam(name = "name", required = true) },
			responses = { @OpenApiResponse(status = "200", description = "The role"),
					@OpenApiResponse(status = "400", description = "Invalid input, or renaming a built-in role"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role"),
					@OpenApiResponse(status = "404", description = "The role does not exist"),
					@OpenApiResponse(status = "409", description = "A role with the new name already exists") })
	public void updateRole(Context ctx) {
		requireAdmin(ctx);
		String name = ctx.pathParam("name");
		Map<String, Object> body = body(ctx);
		try {
			ctx.json(role(roles.update(name, string(body, "name"), string(body, "description"))));
		} catch (IllegalArgumentException | NotAllowedException e) {
			throw new BadRequestResponse(e.getMessage());
		} catch (NotFoundException e) {
			throw new NotFoundResponse(e.getMessage());
		} catch (RoleAlreadyExistsException e) {
			throw new ConflictResponse(e.getMessage());
		} catch (Exception e) {
			logger.error("Failed to change role '{}'", name, e);
			ctx.status(500).json(Map.of("title", "The role could not be changed: " + e.getMessage()));
		}
	}

	@OpenApi(path = "/management/roles/{name}", methods = HttpMethod.DELETE, summary = "Delete a role",
			description = "Deletes the role and removes it from every user that has it. The built-in roles (admin, user) "
					+ "cannot be deleted. Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			pathParams = { @OpenApiParam(name = "name", required = true) },
			responses = { @OpenApiResponse(status = "200", description = "{name}"),
					@OpenApiResponse(status = "400", description = "A built-in role"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role"),
					@OpenApiResponse(status = "404", description = "The role does not exist") })
	public void deleteRole(Context ctx) {
		requireAdmin(ctx);
		String name = ctx.pathParam("name");
		try {
			roles.delete(name);
			ctx.json(Map.of("name", name));
		} catch (NotAllowedException e) {
			throw new BadRequestResponse(e.getMessage());
		} catch (NotFoundException e) {
			throw new NotFoundResponse(e.getMessage());
		} catch (Exception e) {
			logger.error("Failed to delete role '{}'", name, e);
			ctx.status(500).json(Map.of("title", "The role could not be deleted: " + e.getMessage()));
		}
	}

	// ---------- privileges ----------

	@OpenApi(path = "/management/privileges", methods = HttpMethod.GET, summary = "List privileges",
			description = "Lists the privileges that can be granted to users and roles: [{name, description}]. "
					+ "READ_ONLY opens the user's database with access_mode=READ_ONLY. Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			responses = { @OpenApiResponse(status = "200", description = "List of privileges"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role") })
	public void listPrivileges(Context ctx) {
		requireAdmin(ctx);
		List<Map<String, Object>> out = new ArrayList<>();
		for (Privileges.Privilege p : Privileges.ALL) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", p.getName());
			m.put("description", p.getDescription());
			out.add(m);
		}
		ctx.json(out);
	}

	@OpenApi(path = "/management/users/{username}/privileges/{privilege}", methods = HttpMethod.PUT,
			summary = "Grant a privilege to a user",
			description = "Grants the privilege to the user. It applies from the user's next connection. Granting a "
					+ "privilege the user already has does nothing. Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			pathParams = { @OpenApiParam(name = "username", required = true),
					@OpenApiParam(name = "privilege", required = true, description = "For example READ_ONLY") },
			responses = { @OpenApiResponse(status = "200", description = "{username, privileges, effectivePrivileges}"),
					@OpenApiResponse(status = "400", description = "Unknown privilege"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role"),
					@OpenApiResponse(status = "404", description = "The user does not exist") })
	public void grantUserPrivilege(Context ctx) {
		setUserPrivilege(ctx, true);
	}

	@OpenApi(path = "/management/users/{username}/privileges/{privilege}", methods = HttpMethod.DELETE,
			summary = "Revoke a privilege from a user",
			description = "Revokes a privilege granted to the user directly. A privilege the user gets through one of "
					+ "its roles stays until it is revoked from the role ('effectivePrivileges' shows it). Requires "
					+ "the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			pathParams = { @OpenApiParam(name = "username", required = true),
					@OpenApiParam(name = "privilege", required = true) },
			responses = { @OpenApiResponse(status = "200", description = "{username, privileges, effectivePrivileges}"),
					@OpenApiResponse(status = "400", description = "Unknown privilege"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role"),
					@OpenApiResponse(status = "404", description = "The user does not exist") })
	public void revokeUserPrivilege(Context ctx) {
		setUserPrivilege(ctx, false);
	}

	@OpenApi(path = "/management/roles/{name}/privileges/{privilege}", methods = HttpMethod.PUT,
			summary = "Grant a privilege to a role",
			description = "Grants the privilege to the role: every user with the role has it from its next connection. "
					+ "Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			pathParams = { @OpenApiParam(name = "name", required = true),
					@OpenApiParam(name = "privilege", required = true, description = "For example READ_ONLY") },
			responses = { @OpenApiResponse(status = "200", description = "{name, privileges}"),
					@OpenApiResponse(status = "400", description = "Unknown privilege"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role"),
					@OpenApiResponse(status = "404", description = "The role does not exist") })
	public void grantRolePrivilege(Context ctx) {
		setRolePrivilege(ctx, true);
	}

	@OpenApi(path = "/management/roles/{name}/privileges/{privilege}", methods = HttpMethod.DELETE,
			summary = "Revoke a privilege from a role",
			description = "Revokes the privilege from the role. Requires the admin role.",
			security = { @OpenApiSecurity(name = "basicAuth") }, tags = { "Management" },
			pathParams = { @OpenApiParam(name = "name", required = true),
					@OpenApiParam(name = "privilege", required = true) },
			responses = { @OpenApiResponse(status = "200", description = "{name, privileges}"),
					@OpenApiResponse(status = "400", description = "Unknown privilege"),
					@OpenApiResponse(status = "403", description = "User does not have the admin role"),
					@OpenApiResponse(status = "404", description = "The role does not exist") })
	public void revokeRolePrivilege(Context ctx) {
		setRolePrivilege(ctx, false);
	}

	private void setUserPrivilege(Context ctx, boolean granted) {
		requireAdmin(ctx);
		String username = ctx.pathParam("username");
		String privilege = ctx.pathParam("privilege");
		try {
			List<String> direct = privileges.setForUser(username, privilege, granted, ctx.attribute("username"));
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("username", username);
			out.put("privileges", direct);
			out.put("effectivePrivileges", privileges.effective(username));
			ctx.json(out);
		} catch (IllegalArgumentException e) {
			throw new BadRequestResponse(e.getMessage());
		} catch (NotFoundException e) {
			throw new NotFoundResponse(e.getMessage());
		} catch (Exception e) {
			logger.error("Failed to {} {} for user '{}'", granted ? "grant" : "revoke", privilege, username, e);
			ctx.status(500).json(Map.of("title", "The privilege could not be changed: " + e.getMessage()));
		}
	}

	private void setRolePrivilege(Context ctx, boolean granted) {
		requireAdmin(ctx);
		String name = ctx.pathParam("name");
		String privilege = ctx.pathParam("privilege");
		try {
			List<String> rolePrivileges = privileges.setForRole(name, privilege, granted, ctx.attribute("username"));
			ctx.json(Map.of("name", name, "privileges", rolePrivileges));
		} catch (IllegalArgumentException e) {
			throw new BadRequestResponse(e.getMessage());
		} catch (NotFoundException e) {
			throw new NotFoundResponse(e.getMessage());
		} catch (Exception e) {
			logger.error("Failed to {} {} for role '{}'", granted ? "grant" : "revoke", privilege, name, e);
			ctx.status(500).json(Map.of("title", "The privilege could not be changed: " + e.getMessage()));
		}
	}

	// ---------- helpers ----------

	private static Map<String, Object> role(RoleRepository.Role r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", r.getName());
		m.put("description", r.getDescription());
		m.put("users", r.getUsers());
		m.put("builtIn", RoleService.isBuiltIn(r.getName()));
		return m;
	}

	/** Throws 403 unless the authenticated user (set by AuthMiddleware from the users tables) has the admin role. */
	private static void requireAdmin(Context ctx) {
		List<String> userRoles = ctx.attribute("userRoles");
		if (userRoles == null || !userRoles.contains(RoleService.ADMIN)) {
			throw new ForbiddenResponse("You need the ADMIN role to perform this action.");
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> body(Context ctx) {
		try {
			Map<String, Object> body = ctx.bodyAsClass(Map.class);
			return body != null ? body : Map.of();
		} catch (Exception e) {
			throw new BadRequestResponse("The body must be a JSON object");
		}
	}

	private static String string(Map<String, Object> body, String key) {
		Object v = body.get(key);
		return v instanceof String ? (String) v : null;
	}

	/** "roles": [..] or, as in earlier versions, "role": "..."; {@code null} when neither is given. */
	private static List<String> roleList(Map<String, Object> body) {
		Object v = body.get("roles");
		if (v instanceof List) {
			List<String> out = new ArrayList<>();
			for (Object o : (List<?>) v) {
				if (!(o instanceof String)) {
					throw new BadRequestResponse("'roles' must be a list of role names");
				}
				out.add((String) o);
			}
			return out;
		}
		if (v != null) {
			throw new BadRequestResponse("'roles' must be a list of role names");
		}
		String single = string(body, "role");
		return single == null || single.isEmpty() ? null : List.of(single);
	}

	private Connection duplicate() throws SQLException {
		return ((DuckDBConnection) metadatadb).duplicate();
	}
}
