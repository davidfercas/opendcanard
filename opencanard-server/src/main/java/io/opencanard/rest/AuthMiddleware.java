package io.opencanard.rest;

import java.util.Base64;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.UnauthorizedResponse;
import io.opencanard.user.User;

/**
 * Protects the /management/* routes. Accepts either
 * <ul>
 * <li>{@code Authorization: Basic <base64(user:password)>}, checked against the users table, or</li>
 * <li>{@code Authorization: Bearer <JWT>}, as issued by {@code POST /api/login}.</li>
 * </ul>
 * On success it stores the {@code userRoles} and {@code username} attributes in the request context,
 * which the endpoint handlers read.
 */
public class AuthMiddleware implements Handler {

	private final OpenCanardApiController controller;

	public AuthMiddleware(OpenCanardApiController controller) {
		this.controller = controller;
	}

	@Override
	public void handle(Context ctx) {
		String authHeader = ctx.header("Authorization");
		if (authHeader == null) {
			throw new UnauthorizedResponse("Missing credentials");
		}

		if (authHeader.startsWith("Basic ")) {
			try {
				String base64 = authHeader.substring(6);
				String decoded = new String(Base64.getDecoder().decode(base64));
				String[] parts = decoded.split(":", 2);
				String username = parts[0];
				String password = parts[1];

				User user = this.controller.authenticateUser(username, password);
				if (user == null) {
					throw new UnauthorizedResponse("Invalid credentials");
				}

				ctx.attribute("userRoles", user.getRoles());
				ctx.attribute("username", user.getUsername());

			} catch (UnauthorizedResponse e) {
				throw e;
			} catch (Exception e) {
				throw new UnauthorizedResponse("Invalid Authorization header");
			}

		} else if (authHeader.startsWith("Bearer ")) {
			try {
				String login = io.opencanard.auth.JwtTokens.verify(authHeader.substring(7));
				// The roles come from the users table, not from the token: a user deleted (or whose role changed)
				// after signing in must not keep the rights the token was issued with.
				User user = this.controller.findUser(login);
				if (user == null) {
					throw new UnauthorizedResponse("The user of this token no longer exists");
				}
				ctx.attribute("userRoles", user.getRoles());
				ctx.attribute("username", user.getUsername());
			} catch (UnauthorizedResponse e) {
				throw e;
			} catch (Exception e) {
				throw new UnauthorizedResponse("Invalid Token");
			}

		} else {
			throw new UnauthorizedResponse("Missing credentials");
		}
	}
}
