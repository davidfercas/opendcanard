# Flight SQL integration tests

`run.sh` starts a throwaway server from the built distribution, creates the users `alice` and `bob`, and runs:

- `test_flight_sql.py` with the ADBC Flight SQL driver (pytest),
- `FlightSqlJdbcCheck.java` with the Apache Arrow Flight SQL JDBC driver,
- the bundled OpenCanard JDBC driver, to check the original protocol still works.

```bash
mvn package
pip install adbc-driver-flightsql adbc-driver-manager pyarrow pytest
bash integration-tests/flight-sql/run.sh
```

Ports 8815 and 8084 must be free. CI (`.github/workflows/build.yml`) runs the same script on every pull request
and push to `main`.
