# Clients

OpenCanard speaks Apache Arrow Flight, so any Flight client can run SQL against it. This page covers
Python and the bundled JDBC driver.

Both examples assume a server on `localhost:8815` and a user created as described in
[Authentication and security](authentication.md#creating-users). The Flight port does not use TLS yet,
so credentials are sent unencrypted; use a trusted network.

## How a query works

1. The client sends a Flight descriptor whose command is the SQL text (`get_flight_info`).
2. The server computes the result schema of the SQL and returns one endpoint with a ticket.
3. The client fetches the results with that ticket (`do_get`) as a stream of Arrow record batches.

Every call must include an `Authorization` header; use `Basic` credentials
(see [Arrow Flight port](authentication.md#arrow-flight-port)).

## Python (PyArrow Flight)

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

## Java / BI tools (JDBC)

Build the project (`mvn package`) and use the shaded driver
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

### JDBC limitations

The driver is intentionally minimal and targets `SELECT` queries. Prepared statements and callable
statements are not supported (`prepareStatement` and `prepareCall` throw
`SQLFeatureNotSupportedException`), and the driver reports itself as not fully JDBC compliant.
Use plain `Statement` objects.

### Debugging the JDBC driver

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
