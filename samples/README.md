# Samples

Minimal clients that connect to an OpenCanard server and run `SELECT 1`. Each one assumes a server on
`localhost:8815` and an existing user (see [Authentication and security](../docs/authentication.md#creating-users));
pass your own host, port, user and password as shown below. More detail on every client is in
[docs/clients.md](../docs/clients.md).

| Sample | Language | Client | Protocol |
| --- | --- | --- | --- |
| [`python/select_one.py`](python/select_one.py) `--client adbc` | Python | ADBC Flight SQL driver | Arrow Flight SQL |
| [`python/select_one.py`](python/select_one.py) `--client flight` | Python | PyArrow Flight | OpenCanard protocol |
| [`java/SelectOne.java`](java/SelectOne.java) `flightsql` | Java | Apache Arrow Flight SQL JDBC driver | Arrow Flight SQL |
| [`java/SelectOne.java`](java/SelectOne.java) `opencanard` | Java | Bundled OpenCanard JDBC driver | OpenCanard protocol |

## Python

```bash
cd samples/python
pip install -r requirements.txt

python select_one.py                    # every client against localhost:8815 as admin
python select_one.py --client adbc --host db.example --port 8815 --user david --password s3cret
```

Expected output:

```
[adbc] SELECT 1 -> 1
[flight] SELECT 1 -> 1
```

## Java

`SelectOne.java` uses only `java.sql`, so it runs straight from source on Java 17+ with the driver jar on the
class path. Arguments: `<flightsql|opencanard> [host] [port] [user] [password]`.

**Bundled driver** (build it first with `mvn package` from the repository root):

```bash
java -cp opencanard-jdbc-driver/target/opencanard-jdbc-driver-0.1-all.jar \
     samples/java/SelectOne.java opencanard localhost 8815 admin change-me
```

**Flight SQL JDBC driver** (download `flight-sql-jdbc-driver-<version>.jar` from
[Maven Central](https://central.sonatype.com/artifact/org.apache.arrow/flight-sql-jdbc-driver)):

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     -cp flight-sql-jdbc-driver-18.3.0.jar \
     samples/java/SelectOne.java flightsql localhost 8815 admin change-me
```

To compile instead of running from source: `javac -d out samples/java/SelectOne.java`, then
`java -cp out:<driver jar> SelectOne ...` (use `;` instead of `:` on Windows).

Both samples also read the password from the `OPENCANARD_PASSWORD` environment variable when it is not passed
on the command line.
