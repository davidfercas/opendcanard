# Authentication and security

> **Read this before exposing OpenCanard to a network.** OpenCanard is in early development. This
> page documents how authentication actually behaves in the current source code, including the gaps.

## Summary

| Area | Current behavior |
| --- | --- |
| Flight port (`8815`) — `Basic` credentials | Checked against the users table (bcrypt). |
| Flight port — `Bearer` token | **Any value is accepted.** The token is not validated. |
| Flight port — transport | **Plaintext gRPC.** No TLS. |
| REST API (`8084`) | Login returns a signed JWT; `/management/*` requires credentials or a JWT. Plain HTTP. |
| JWT signing secret | **Hard-coded** in the source. |
| Query restrictions | **None.** SQL from clients runs as-is on a read-write DuckDB connection. |

Until the gaps below are fixed, run OpenCanard only on a trusted network (loopback, a private network,
or behind a reverse proxy / VPN that provides TLS and access control), and do not rely on it to keep
data away from other users of that network.

## Users

Users live in the table `main.opencanard_users` of the **metadata** database
(`metadata/metadata.duckdb`). Each user has one role. The REST management endpoints (except
shutdown, see [known limitations](#known-limitations)) require the role `admin`, written exactly like
that in lowercase; any other value gives no management access.

### Creating users

An admin can create users through the REST API, see [Creating a user](rest-api.md#creating-a-user). Each
user created that way also gets a personal database registered in `opencanard_databases`, see
[Per-user databases](#per-user-databases).
The sections below describe the first admin and manual creation.

The first time the server starts without a `metadata/metadata.duckdb` file, it creates the file, the
table below and one admin user taken from `admin.user` / `admin.password` (the password is stored as a
bcrypt hash):

```sql
CREATE TABLE opencanard_users(
    username      VARCHAR PRIMARY KEY,
    password_hash VARCHAR NOT NULL,
    "role"        VARCHAR NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
```

If the file already exists, nothing is created or changed. To add more users, open the metadata
database with the [DuckDB CLI](https://duckdb.org/docs/installation/) and run:

```sql
INSERT INTO main.opencanard_users (username, password_hash, role, created_at)
VALUES ('someone', '<bcrypt hash>', 'admin', now());
```

DuckDB allows only one process to open a database file for writing, so stop the server before running
the CLI against `metadata.duckdb`.

Generate the bcrypt hash with any bcrypt tool. Two examples:

```bash
# Python (pip install bcrypt)
python3 -c "import bcrypt; print(bcrypt.hashpw(b'change-me', bcrypt.gensalt(10)).decode())"

# Apache htpasswd
htpasswd -bnBC 10 "" 'change-me' | tr -d ':\n'
```

The server verifies passwords with Spring Security's `BCryptPasswordEncoder`, which accepts the
`$2a$`, `$2b$` and `$2y$` prefixes. Never store a plaintext password in the table.

## Per-user databases

The metadata database has a second table, `opencanard_databases`, which says which DuckDB file each login
connects to:

```sql
CREATE TABLE opencanard_databases(
    login         VARCHAR NOT NULL,
    database_name VARCHAR NOT NULL,     -- the login, for the default database
    duckdb_file   VARCHAR NOT NULL,     -- file name inside data/users/
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (login, database_name)
);
```

On every Arrow Flight call the server looks the caller up in this table and opens a connection to the
registered file (`data/users/<duckdb_file>`). Each call gets its own connection; the file is opened on the
first call and kept open until the server stops.

- **Admins** (role `admin`, including the admin created on the first start) do not use a personal database:
  their Flight calls run on the **metadata** database, so an admin can query `opencanard_users` and
  `opencanard_databases`. The role is read from `opencanard_users` on every call. Because that database holds
  the password hashes, admin Flight access is full read-write access to it.
- **Every other user** must have a row in `opencanard_databases`. A login without one, a missing file, or the
  unvalidated `Bearer` tokens (see below, they have no registered login) are refused with an error. There is no
  shared database fallback.
- **Creating a user** through the REST API creates the file `data/users/<login>.duckdb`, the row in
  `opencanard_databases` and the row in `opencanard_users`, and removes whatever was created if one step fails.
- **At every start** the server checks the registry: each non-admin user without a row gets one (an existing
  `<login>.duckdb` is registered, otherwise an empty file is created and registered), and a registered file
  that does not exist is logged as an error. Look for `[user-db]` in the log. Users inserted by hand in SQL are
  therefore picked up at the next start.

## Arrow Flight port

Every Flight call must carry an `Authorization` header. Two schemes are recognized:

- **`Basic <base64(user:password)>`** — the credentials are checked against the users table.
  Wrong credentials are rejected with `UNAUTHENTICATED`.
- **`Bearer <token>`** — **accepted without validation.** The authenticator currently treats every
  bearer token as valid. Anyone who can reach the port can send `Authorization: Bearer x` and run
  queries. The server does not issue bearer tokens on the Flight port either.

Clients should therefore send `Basic` credentials on each call (see [clients](clients.md)). Do not
treat the Flight port as access-controlled until bearer-token validation is implemented.

Once authenticated, there is no per-user or per-role authorization on the Flight port: every user can
run any SQL statement the DuckDB connection allows, including statements that modify data.

## TLS

The sample configuration contains `tls.enabled=true`, `tls.certFile` and `tls.keyFile`, but **these
settings are not read by the server**. The Flight server is created with an insecure gRPC location and
the REST API uses plain HTTP, so credentials and query results travel unencrypted.

To protect traffic today, terminate TLS in front of OpenCanard (for example a reverse proxy for the
REST API, or a VPN / private network for the Flight port).

## REST API authentication

See [REST API](rest-api.md) for the endpoints. In short:

- `POST /api/login` exchanges a username and password for a JWT (HS256, valid for one hour, carrying the
  `username` and `roles` claims).
- Everything under `/management/*` requires either `Authorization: Basic ...` or
  `Authorization: Bearer <JWT>`. These JWTs **are** verified, unlike the Flight bearer tokens.
- The JWT secret is a constant in the source code (`your-super-secret-key-change-this`). Anyone who
  knows it can forge a token with the `admin` role. Rebuild with your own secret if you must run a
  build from this version outside a trusted network.

## Known limitations

These are tracked here so they are not mistaken for features:

1. Flight bearer tokens are not validated.
2. No TLS on either port; the `tls.*` properties are ignored.
3. The JWT signing secret is hard-coded.
4. `POST /management/shutdown` only requires an authenticated user, not the `admin` role.
5. No read-only enforcement: clients can send `INSERT`, `UPDATE`, `DROP` and other statements.
6. Only the first admin is created automatically; further users must be added by hand in the metadata database.
