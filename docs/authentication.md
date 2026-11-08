# Authentication and security

> **Read this before exposing OpenCanard to a network.** OpenCanard is in early development. This
> page documents how authentication actually behaves in the current source code, including the gaps.

## Summary

| Area | Current behavior |
| --- | --- |
| Flight port (`8815`) — `Basic` credentials | Checked against the users table (bcrypt). |
| Flight port — `Bearer` token | Issued by the server after a successful `Basic` call; unknown or expired tokens are refused. |
| Flight port — transport | **Plaintext gRPC.** No TLS. |
| REST API (`8084`) | Login returns a signed JWT; `/management/*` requires credentials or a JWT. Plain HTTP. |
| JWT signing secret | **Hard-coded** in the source. |
| Query restrictions | **None.** SQL from clients runs as-is on a read-write DuckDB connection. |

Until the gaps below are fixed, run OpenCanard only on a trusted network (loopback, a private network,
or behind a reverse proxy / VPN that provides TLS and access control), and do not rely on it to keep
data away from other users of that network.

## Users

Users live in the table `main.opencanard_users` of the **metadata** database
(`metadata/metadata.duckdb`). A user can have several roles, see [Roles](#roles). The REST management
endpoints (except shutdown, see [known limitations](#known-limitations)) require the role `admin`, written
exactly like that in lowercase; no other role gives management access.

### Roles

Roles are kept in their own tables, also in the metadata database:

```sql
CREATE TABLE opencanard_roles(
    name        VARCHAR PRIMARY KEY,
    description VARCHAR NOT NULL DEFAULT ''
);

CREATE TABLE opencanard_user_roles(       -- which roles each user has
    username  VARCHAR NOT NULL,
    role_name VARCHAR NOT NULL,
    PRIMARY KEY (username, role_name)
);
```

- `admin` and `user` are built in: the server adds them at every start if they are missing, and they cannot
  be deleted or renamed (their description can change). `admin` gives the administration (users and roles) and
  the metadata database; `user` is the default role of a new user.
- Admins create, change and delete the other roles from the console (**Administration → Roles**) or the
  REST API, see [Roles](rest-api.md#roles). Renaming a role keeps it on every user that has it; deleting it
  removes it from them.
- The **built-in admin user** (`admin.user`) can never be deleted and always keeps the role `admin`; the server
  gives the role back at startup if it was removed by hand. Nobody can delete their own account or remove `admin`
  from themselves.

Earlier versions kept one role per user in a `role` column of `opencanard_users`. At the first start of this
version the server copies every value into `opencanard_user_roles` (adding any role that is not in
`opencanard_roles`) and drops the column.

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
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
```

If the file already exists, nothing is created or changed. To add more users, open the metadata
database with the [DuckDB CLI](https://duckdb.org/docs/installation/) and run:

```sql
INSERT INTO main.opencanard_users (username, password_hash, created_at)
VALUES ('someone', '<bcrypt hash>', now());
INSERT INTO main.opencanard_user_roles (username, role_name) VALUES ('someone', 'admin');
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
  `opencanard_databases`. The roles are read from `opencanard_user_roles` on every call. Because that database holds
  the password hashes, admin Flight access is full read-write access to it.
- **Every other user** must have a row in `opencanard_databases`. A login without one, a missing file, or the
  unknown `Bearer` token is refused with an error. There is no shared database fallback.
- **Creating a user** through the REST API creates the file `data/users/<login>.duckdb`, the row in
  `opencanard_databases`, the row in `opencanard_users` and its roles, and removes whatever was created if one step
  fails. A user that loses the role `admin` gets its personal database at that moment if it has none.
- **At every start** the server checks the registry: each non-admin user without a row gets one (an existing
  `<login>.duckdb` is registered, otherwise an empty file is created and registered), and a registered file
  that does not exist is logged as an error. Look for `[user-db]` in the log. Users inserted by hand in SQL are
  therefore picked up at the next start.

## Arrow Flight port

Every Flight call must carry an `Authorization` header. Two schemes are recognized:

- **`Basic <base64(user:password)>`** — the credentials are checked against the users table.
  Wrong credentials are rejected with `UNAUTHENTICATED`.
- **`Bearer <token>`** — a token the server issued. Every call that succeeds with `Basic` credentials gets
  `Authorization: Bearer <token>` back in its response headers, and the client may send that instead from then
  on. This is how Flight SQL clients (ADBC, the Flight SQL JDBC driver) work: they log in once with `Basic` and
  continue with the token. Tokens are random, kept in the server's memory only (a restart forgets them) and expire
  after one hour without use. Any other Bearer value is rejected with `UNAUTHENTICATED`.

The REST API's JWTs are not accepted on the Flight port. The same port serves both Arrow Flight SQL and
OpenCanard's own protocol (see [clients](clients.md)); authentication is the same for both.

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

1. Flight bearer tokens are kept in memory; they are not shared between servers and do not survive a restart.
2. No TLS on either port; the `tls.*` properties are ignored.
3. The JWT signing secret is hard-coded.
4. `POST /management/shutdown` only requires an authenticated user, not the `admin` role.
5. No read-only enforcement: clients can send `INSERT`, `UPDATE`, `DROP` and other statements.
6. Only the first admin is created automatically; further users must be added by hand in the metadata database.
