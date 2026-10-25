package io.opencanard.rest;

/** Response of {@code POST /management/users}. Used for the OpenAPI documentation. */
public class CreateUserResponse {
    public String username;
    public String role;
    /** File name of the user's own DuckDB database. */
    public String database;
}
