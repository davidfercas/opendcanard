# Clients

The Flight port (`8815`) speaks two protocols at once:

- **Arrow Flight SQL**, the standard protocol: ADBC drivers (Python, Go, C++, R, Java), the Apache Arrow
  Flight SQL JDBC driver, Flight SQL ODBC drivers, and the tools built on them (DBeaver, SQLAlchemy, Ibis,
  Superset, Metabase...). See [Flight SQL clients](#flight-sql-clients). This is the recommended way to connect.
- **OpenCanard's own protocol**, where the Flight command is the SQL text: plain PyArrow Flight code, the bundled
  JDBC driver and the DBeaver plugin. See [OpenCanard protocol](#opencanard-protocol).

The server tells them apart by the request itself, so there is nothing to configure. Every example assumes a
server on `localhost:8815` and a user created as described in
[Authentication and security](authentication.md#creating-users). The Flight port does not use TLS yet,
so credentials are sent unencrypted; use a trusted network.

Whatever the client, each user works on their own database: admins on the metadata database, every other
user on the DuckDB file registered for them (see [Per-user databases](authentication.md#per-user-databases)).

## Flight SQL clients

Log in with your OpenCanard user name and password. The server answers the first call with a Bearer token
that the client uses from then on; it expires after an hour without use, and clients log in again by
themselves.

### Python (ADBC)

```bash
pip install adbc-driver-flightsql pyarrow
```

```python
import adbc_driver_flightsql.dbapi as flight_sql

conn = flight_sql.connect(
    "grpc://localhost:8815",
    db_kwargs={"username": "david", "password": "s3cret"},
    autocommit=True,
)
with conn.cursor() as cur:
    cur.execute("CREATE TABLE IF NOT EXISTS fruit(id INTEGER, name VARCHAR)")
    cur.executemany("INSERT INTO fruit VALUES (?, ?)", [(1, "apple"), (2, "banana")])
    cur.execute("SELECT * FROM fruit WHERE id = ?", (2,))
    print(cur.fetch_arrow_table())

print(conn.adbc_get_table_schema("fruit", db_schema_filter="main"))
```

Pass `autocommit=True`: OpenCanard does not support transactions (every statement commits on its own), and
without it the driver warns that it cannot turn autocommit off. pandas 2.2 and later accept the connection
directly: `pd.read_sql("SELECT * FROM fruit", conn)`.

### Java / BI tools (Flight SQL JDBC driver)

Use the standard [Apache Arrow Flight SQL JDBC driver](https://arrow.apache.org/docs/java/flight_sql_jdbc_driver.html)
(Maven `org.apache.arrow:flight-sql-jdbc-driver`; in DBeaver, pick the Flight SQL driver from its driver
list, or add the jar as a custom driver).

| Setting | Value |
| --- | --- |
| Driver class | `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver` |
| URL | `jdbc:arrow-flight-sql://localhost:8815/?useEncryption=false` |
| `user` / `password` | OpenCanard user name and password |

`useEncryption=false` is required while the Flight port has no TLS. Unlike the bundled driver, this one
supports prepared statements with parameters, updates, and the `DatabaseMetaData` calls BI tools use to browse
catalogs, schemas, tables, columns and primary keys. On JVMs 16 and later, start the client with
`--add-opens=java.base/java.nio=ALL-UNNAMED`, as for any Arrow Java code.

### What is supported

| Flight SQL feature | Support |
| --- | --- |
| Statements (`CommandStatementQuery`) | Yes. DDL and DML sent as a query run too; they return no rows. |
| Updates (`CommandStatementUpdate`) | Yes, with the number of changed rows. |
| Prepared statements with parameters | Yes, parameters bound from Arrow. Several parameter rows on an update run as one batch; on a query only the last row is used. |
| Scripts (several statements) | As statements. A prepared script takes no parameters, and a JDBC client gets no rows from it. |
| Catalogs, schemas, tables (with Arrow schemas), table types, primary keys | Yes, from the user's own database. |
| `GetSqlInfo` | Yes (server name `OpenCanard`, no transactions, cancel supported). |
| Foreign keys, cross references, type info | Answered with empty results. |
| Cancel (`CancelFlightInfo`) | Yes. Every query also shows up, and can be killed, in the web console's *Running queries* (source *Arrow Flight SQL*). |
| Transactions, savepoints, bulk ingestion, Substrait | No. |

Results and prepared statements a client leaves open are closed after 10 minutes without use.

## OpenCanard protocol

### How a query works

1. The client sends a Flight descriptor whose command is the SQL text (`get_flight_info`).
2. The server computes the result schema of the SQL and returns one endpoint with a ticket.
3. The client fetches the results with that ticket (`do_get`) as a stream of Arrow record batches.

Every call must include an `Authorization` header: `Basic` credentials, or the Bearer token the server sent
back after a Basic call (see [Arrow Flight port](authentication.md#arrow-flight-port)).

### Python (PyArrow Flight)

```bash
pip install pyarrow
```

```python
import base64
import pyarrow.flight as flight

client = flight.FlightClient("grpc://localhost:8815")

credentials = base64.b64encode(b"admin:change-me").decode()
options = flight.FlightCallOptions(
    headers=[(b"authorization", f"Basic {credentials}".encode())]
)

sql = "SELECT 42 AS answer"
descriptor = flight.FlightDescriptor.for_command(sql.encode())
info = client.get_flight_info(descriptor, options)

reader = client.do_get(info.endpoints[0].ticket, options)
table = reader.read_all()
print(table.to_pandas())
```

Parquet and object-storage data is queried with DuckDB's own functions inside the SQL, for example
`SELECT * FROM read_parquet('/data/events/*.parquet')`.

### Java / BI tools (bundled JDBC driver)

Prefer the [Flight SQL JDBC driver](#java--bi-tools-flight-sql-jdbc-driver), which does more. The bundled
driver remains for existing setups. Build the project (`mvn package`) and use the shaded driver
`opencanard-jdbc-driver/target/opencanard-jdbc-driver-0.1-all.jar`.

| Setting | Value |
| --- | --- |
| Driver class | `io.opencanard.driver.jdbc.OpenCanardDriver` |
| URL | `jdbc:opencanard://<host>[:<port>]` (port defaults to `8815`; IPv6 hosts go in brackets, e.g. `jdbc:opencanard://[::1]:8815`) |
| `user` | OpenCanard username |
| `password` | OpenCanard password |

The jar does not include a `META-INF/services` registration for `java.sql.Driver`, so tools that
discover drivers automatically may not find it. Configure the driver class explicitly, or load it
with `Class.forName(...)` as below.

```java
import java.sql.*;
import java.util.Properties;

public class Example {
    public static void main(String[] args) throws Exception {
        Class.forName("io.opencanard.driver.jdbc.OpenCanardDriver");

        Properties props = new Properties();
        props.setProperty("user", "admin");
        props.setProperty("password", "change-me");

        try (Connection conn = DriverManager.getConnection("jdbc:opencanard://localhost:8815", props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 42 AS answer")) {
            while (rs.next()) {
                System.out.println(rs.getInt("answer"));
            }
        }
    }
}
```

#### JDBC limitations

The driver is intentionally minimal and targets `SELECT` queries. Prepared statements and callable
statements are not supported (`prepareStatement` and `prepareCall` throw
`SQLFeatureNotSupportedException`), and the driver reports itself as not fully JDBC compliant.
Use plain `Statement` objects.

#### Debugging the JDBC driver

Turn on debug output with any of:

- JVM option `-Dopencanard.jdbc.debug=true` (for Denodo, add it to the VDP server JVM options);
- environment variable `OPENCANARD_JDBC_DEBUG=true`;
- `debug=true` as a connection property, or in the URL: `jdbc:opencanard://host:8815/?debug=true`.

Output goes to stderr and is appended to `<java.io.tmpdir>/opencanard-jdbc-debug.log` (change it with
`-Dopencanard.jdbc.debug.file=<path>`). It shows the driver jar location, the Java and class loader
details, where gRPC, Netty, Guava, Protobuf and Arrow are loaded from, each connection (the password is
never written), each SQL statement and its timings. The same lines go to log4j when the logger
`io.opencanard.driver.jdbc` is set to DEBUG.

SQL text is logged, so do not leave debug on in production.

Connection failures are always reported, even with debug off: the full cause chain goes to stderr and into
the `SQLException` message. This matters for `Could not initialize class ...` errors, because the JVM only
reports the real cause the first time; the driver keeps that first failure and repeats it in later errors.

`OpenCanardJdbcClient` is a test client: `java -cp <driver jar> io.opencanard.driver.jdbc.OpenCanardJdbcClient [url] [user] [password] [sql]`.

#### DBeaver

DBeaver's Flight SQL driver connects with the settings in
[Flight SQL JDBC driver](#java--bi-tools-flight-sql-jdbc-driver). Alternatively,
`opencanard-dbeaver-connector/` contains a DBeaver plugin that adds OpenCanard, with its logo, to the New
Connection dialog. See its [README](../opencanard-dbeaver-connector/README.md) for how to build and install it.
