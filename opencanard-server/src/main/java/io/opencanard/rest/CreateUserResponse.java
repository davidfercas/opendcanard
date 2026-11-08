package io.opencanard.rest;

import java.util.List;

/** Response of {@code POST /management/users}. Used for the OpenAPI documentation. */
public class CreateUserResponse {
    public String username;
    public List<String> roles;
    /** File name of the user's own DuckDB database. */
    public String database;
}
