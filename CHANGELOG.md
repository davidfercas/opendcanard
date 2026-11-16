# Changelog

All notable changes to OpenCanard are listed here. Versions follow [Semantic Versioning](https://semver.org);
before 1.0.0, a minor version may break compatibility.

## [0.1.0] - unreleased

First release.

### Querying
- DuckDB behind an Apache Arrow Flight endpoint, with results streamed as Arrow record batches.
- Arrow Flight SQL: ADBC, the Flight SQL JDBC and ODBC drivers connect directly, with prepared statements,
  parameters, updates and catalog metadata.
- OpenCanard's own Flight protocol (the Flight command is the SQL text), for PyArrow Flight code and the bundled
  JDBC driver.
- One DuckDB database per user.
- S3-compatible storages per user, with secret keys encrypted in the metadata database, bucket browsing and a
  connection test.

### Users and security
- Users, roles and the `READ_ONLY` privilege, managed in the web console or the REST API; bcrypt-hashed passwords.
- Basic credentials and Bearer tokens on the Flight port; JWT login on the REST API. The JWT signing key is
  generated per installation (`metadata/jwt.key`) or set with `OPENCANARD_JWT_SECRET`.
- Management endpoints, including shutdown, require the `admin` role.
- TLS through a reverse proxy, with documented nginx configurations for gRPC and HTTPS.

### Web console
- SQL editor with query history, running queries (with kill), S3 storages, and an administration area for users,
  roles and privileges.

### Clients and deployment
- JDBC driver (`opencanard-jdbc-driver-0.1.0-all.jar`) with TLS options, and a DBeaver plugin.
- Server distribution zip with start scripts, a Docker image, and a systemd install script for Red Hat family
  distributions.
- REST API documented with OpenAPI and Swagger UI.
- Python and Java samples.

### Known limitations
See [docs/authentication.md](docs/authentication.md#known-limitations): no native TLS (#10), no SQL sandbox
for client queries (#13), Flight bearer tokens kept in memory, and no way to change a password except in SQL.

[0.1.0]: https://github.com/davidfercas/opendcanard/releases/tag/v0.1.0
