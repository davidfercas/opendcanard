# REST API

OpenCanard ships a small REST API for health checks and administration. It listens on
`opencanard.rest.port` (default `8084`) over plain HTTP — see
[Authentication and security](authentication.md#tls).

The API is documented with OpenAPI. The server registers the Javalin OpenAPI and Swagger plugins, so
a Swagger UI and the OpenAPI document are served by the running server. They are expected at the
plugins' default paths, `/swagger` and `/openapi`; these paths were taken from the plugin defaults and
not checked against a running instance.

## Endpoints

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| `GET` | `/health` | none | Returns `{"status": "UP", "engine": "DuckDB"}`. |
| `POST` | `/api/login` | none | Exchanges a username and password for a JWT. |
| `GET` | `/management/databases` | admin | Lists the databases attached to the DuckDB engine (`PRAGMA show_databases`). |
| `GET` | `/management/schemas` | admin | Lists the schemas of the engine (rows of `duckdb_schemas()`). |
| `GET` | `/management/users` | admin | Lists users: `id`, `username`, `role`, `created_at`. Password hashes are not returned. |
| `POST` | `/management/shutdown` | any authenticated user | Stops the server process immediately. |

Error responses: `401` for missing or invalid credentials, `403` when the `admin` role is required and
missing, `500` for database errors.

## Authentication

### Login

```bash
curl -s -X POST http://localhost:8084/api/login \
  -H 'Content-Type: application/json' \
  -d '{"username": "admin", "password": "change-me"}'
```

```json
{ "token": "<JWT>" }
```

The token is a JWT signed with HS256 and valid for one hour. It carries the claims `username` and
`roles`.

### Calling management endpoints

Send either the JWT or HTTP Basic credentials on every request to `/management/*`:

```bash
TOKEN=<JWT from /api/login>

curl -s http://localhost:8084/management/databases -H "Authorization: Bearer $TOKEN"

curl -s http://localhost:8084/management/users -u admin:change-me
```

## Notes and limitations

- `/management/shutdown` calls `System.exit(0)` and, unlike the other management endpoints, does not
  check for the `admin` role.
- The JWT signing secret is a constant in the source code, so tokens can be forged by anyone who has
  read it. See [Known limitations](authentication.md#known-limitations).
- The users table must exist in the metadata database before login or any management call will work.
  See [Creating users](authentication.md#creating-users).
