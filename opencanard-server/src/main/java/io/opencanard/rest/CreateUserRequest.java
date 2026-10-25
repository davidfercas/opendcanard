package io.opencanard.rest;

/** Body of {@code POST /management/users}. Used for the OpenAPI documentation. */
public class CreateUserRequest {
    public String username;
    public String password;
    /** {@code admin} or {@code user}; defaults to {@code user}. */
    public String role;
}
