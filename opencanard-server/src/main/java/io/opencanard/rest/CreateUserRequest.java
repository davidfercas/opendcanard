package io.opencanard.rest;

import java.util.List;

/** Body of {@code POST /management/users}. Used for the OpenAPI documentation. */
public class CreateUserRequest {
    public String username;
    public String password;
    /** Names of existing roles; defaults to {@code ["user"]}. */
    public List<String> roles;
    /** Single role, accepted for compatibility with earlier versions when {@code roles} is not given. */
    public String role;
}
