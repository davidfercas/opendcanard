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
| `GET` | `/management/users` | admin | Lists users: `username`, `role`, `created_at`. Password hashes are not returned. |
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
- Login and the management calls read the users table in the metadata database. The server creates it
  with the first admin user on the first start. See [Creating users](authentication.md#creating-users).

## Code layout

All REST code is in the `io.opencanard.rest` package; `OpenCanardServer` only creates, starts and stops it.

| Class | Role |
| --- | --- |
| `RestApiServer` | Initializes Javalin: OpenAPI and Swagger plugins, port, start and stop. |
| `RestRoutes` | The route table: which URL goes to which handler. |
| `AuthMiddleware` | Basic / Bearer authentication for `/management/*`. |
| `OpenCanardApiController` | The endpoint handlers and their `@OpenApi` annotations. |
| `LoginRequest`, `LoginResponse` | Request and response bodies of `POST /api/login`. |

To add an endpoint, add a handler method (with its `@OpenApi` annotation) to `OpenCanardApiController` and
register it in `RestRoutes`.
