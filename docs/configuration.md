# Configuration

OpenCanard is configured through a properties file plus a few JVM system properties that the start
scripts set for you.

> This page describes what the current source code does. Properties that exist in the sample file
> but are not read by the server yet are marked as such.

## Distribution layout

`mvn package` builds `opencanard-server/target/opencanard.zip`, which unpacks to:

```
opencanard/
├── bin/        start.sh, start.bat
├── conf/       opencanard.properties, log4j2.xml
├── lib/        opencanard-server-0.1.jar and external/ (dependencies)
├── data/       the analytics DuckDB database (created at runtime)
├── metadata/   the metadata DuckDB database with users (created at runtime)
└── logs/       log output (created at runtime)
```

Run `bin/start.sh` (or `bin\start.bat` on Windows) from any directory; the scripts resolve the
installation directory themselves.

## `conf/opencanard.properties`

| Property | Default | Effect |
| --- | --- | --- |
| `opencanard.host` | `0.0.0.0` | Interface the Arrow Flight server binds to. |
| `opencanard.port` | `8815` | Arrow Flight (gRPC) port. |
| `opencanard.rest.port` | `8084` | REST API port. |
| `opencanard.db` | `opencanard.duckdb` | File name of the analytics database, inside `data/`. |
| `metadatadb.db` | `metadata.duckdb` | File name of the metadata database, inside `metadata/`. |
| `arrow.memory.mb` | `512` | Upper limit, in MB, of the Arrow memory allocator. |

These properties are present in the sample file but **are not read by the server yet**:

| Property | Note |
| --- | --- |
| `tls.enabled`, `tls.certFile`, `tls.keyFile` | The Flight server always starts without TLS. See [Authentication and security](authentication.md#tls). |
| `log.level`, `log.file` | Logging is configured only in `conf/log4j2.xml`. |

`arrow.memory.mb` appears twice in the sample file; the value is the same in both places, so the
duplicate can be deleted.

## System properties

The start scripts pass these to the JVM. All six are required; the server exits with
`Missing system property` if one is absent.

| Property | Set by the scripts to | Purpose |
| --- | --- | --- |
| `conf.dir` | `<install>/conf` | Directory containing `opencanard.properties`. |
| `data.dir` | `<install>/data` | Location of the analytics database. |
| `metadata.dir` | `<install>/metadata` | Location of the metadata database. |
| `log.dir` | `<install>/logs` | Log directory passed to the application. |
| `admin.user` | `$OPENCANARD_ADMIN_USER` | Username of the admin created when the metadata database is created. |
| `admin.password` | `$OPENCANARD_ADMIN_PASSWORD` | Password of that admin (stored as a bcrypt hash). |

`admin.user` and `admin.password` are only *used* when `metadata/metadata.duckdb` does not exist yet;
afterwards they are still required but have no effect, and changing them does not change the stored user.

The scripts also set `--add-opens=java.base/java.nio=ALL-UNNAMED` (required by Apache Arrow on
Java 17), `-Xms512m`, `-Xmx2g` and `-Dfile.encoding=UTF-8`. The heap can be changed without editing the script through the `OPENCANARD_XMS` and `OPENCANARD_XMX`
environment variables; `OPENCANARD_JAVA_OPTS` appends extra JVM options and `JAVA_HOME` selects the JDK.
See [Deployment](deployment.md) for the systemd service and Docker image.

## Logging

Logging is defined in `conf/log4j2.xml`: INFO level to the console and to `logs/opencanard.log`.
The start scripts load it with `-Dlog4j.configurationFile=<install>/conf/log4j2.xml`, because `conf/` is not
on the classpath. If you start the JVM by other means, pass that property yourself; without it log4j uses
its built-in default and only prints ERROR messages to the console. Add `-Dlog4j2.debug=true` to see which
configuration file log4j loaded.
The log file path in that file is relative to the **working directory** the server is started from,
not to `log.dir`. `bin/start.sh` changes into the installation directory before starting the server, so
the log ends up in `logs/`; if you start the JVM by other means, do it from the installation directory.

## Databases and startup behavior

- The analytics database (`data/opencanard.duckdb`) and the metadata database
  (`metadata/metadata.duckdb`) are opened in read-write mode and are created if they do not exist.
  When the metadata database is created, the server also creates the `opencanard_users` table and
  inserts the admin user from `admin.user` / `admin.password`.
- On startup the server runs `INSTALL arrow;` and `LOAD arrow;` in DuckDB. DuckDB downloads
  extensions on first use, so the first start may need Internet access.
- To query Parquet files or S3 data, use DuckDB's own functions from your SQL
  (for example `SELECT * FROM read_parquet('...')`). Configuring S3 credentials is not exposed
  through OpenCanard's properties.
