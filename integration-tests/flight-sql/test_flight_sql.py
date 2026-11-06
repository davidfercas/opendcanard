"""Arrow Flight SQL integration tests, run against a live OpenCanard server by run.sh.

Covers the standard ADBC Flight SQL driver (statements, updates, prepared statements with parameters, catalog
metadata, user to database routing, errors) and checks that OpenCanard's own protocol (raw SQL in the Flight
descriptor, used by the bundled JDBC driver and DBeaver plugin) still works next to it.
"""
import base64
import os

import adbc_driver_flightsql.dbapi as flight_sql
import adbc_driver_manager
import pyarrow.flight as flight
import pytest

URI = os.environ.get("OC_FLIGHT_URI", "grpc://localhost:8815")
ADMIN = (os.environ.get("OC_ADMIN_USER", "admin"), os.environ.get("OC_ADMIN_PASSWORD", "admin-pw"))
ALICE = ("alice", "alice-pw")
BOB = ("bob", "bob-pw")


def connect(user):
    return flight_sql.connect(URI, db_kwargs={"username": user[0], "password": user[1]}, autocommit=True)


def query(conn, sql, params=None):
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.fetch_arrow_table()


@pytest.fixture(scope="module")
def alice():
    conn = connect(ALICE)
    with conn.cursor() as cur:
        cur.execute("DROP TABLE IF EXISTS fruit")
        cur.execute("CREATE TABLE fruit(id INTEGER PRIMARY KEY, name VARCHAR NOT NULL, price DECIMAL(6,2))")
    yield conn
    conn.close()


# ---------- statements ----------

def test_select_returns_arrow_types(alice):
    table = query(alice, "SELECT 42 AS answer, 'duck' AS word, 1.5::DOUBLE AS d, DATE '2026-11-07' AS day")
    assert table.num_rows == 1
    assert table.column("answer").to_pylist() == [42]
    assert table.column("word").to_pylist() == ["duck"]
    assert str(table.schema.field("day").type).startswith("date32")


def test_large_result_streams_in_batches(alice):
    table = query(alice, "SELECT range AS n FROM range(100000)")
    assert table.num_rows == 100000
    assert table.column("n").to_pylist()[-1] == 99999


def test_nested_types(alice):
    table = query(alice, "SELECT [1, 2, 3] AS l, {'a': 1, 'b': 'x'} AS s")
    assert table.column("l").to_pylist() == [[1, 2, 3]]
    assert table.column("s").to_pylist() == [{"a": 1, "b": "x"}]


def test_ddl_through_query_path(alice):
    with alice.cursor() as cur:
        cur.execute("CREATE OR REPLACE TABLE scratch AS SELECT 1 AS x")
        cur.fetch_arrow_table()  # no rows, but must not fail
    assert query(alice, "SELECT x FROM scratch").column("x").to_pylist() == [1]


def test_execute_update_counts_rows(alice):
    stmt = adbc_driver_manager.AdbcStatement(alice.adbc_connection)
    try:
        stmt.set_sql_query("INSERT INTO fruit VALUES (100, 'temp', 1.00), (101, 'temp', 2.00)")
        assert stmt.execute_update() == 2
        stmt.set_sql_query("DELETE FROM fruit WHERE name = 'temp'")
        assert stmt.execute_update() == 2
    finally:
        stmt.close()


# ---------- prepared statements ----------

def test_prepared_insert_and_select(alice):
    with alice.cursor() as cur:
        cur.execute("INSERT INTO fruit VALUES (?, ?, ?)", (1, "apple", 1.25))
        cur.executemany("INSERT INTO fruit VALUES (?, ?, ?)", [(2, "banana", 0.5), (3, "cherry", 4.0)])
    table = query(alice, "SELECT name FROM fruit WHERE id = ?", (2,))
    assert table.column("name").to_pylist() == ["banana"]
    table = query(alice, "SELECT count(*) AS c FROM fruit WHERE id <= ?", (3,))
    assert table.column("c").to_pylist() == [3]


def test_prepared_statement_runs_again_with_new_parameters(alice):
    with alice.cursor() as cur:
        names = []
        for i in (1, 3):
            cur.execute("SELECT name FROM fruit WHERE id = ?", (i,))
            names.append(cur.fetchone()[0])
    assert names == ["apple", "cherry"]


# ---------- catalog metadata ----------

def test_get_info_names_the_server(alice):
    info = alice.adbc_get_info()
    assert info.get("vendor_name") == "OpenCanard"


def test_get_objects_lists_the_users_own_table(alice):
    objects = alice.adbc_get_objects(depth="all").read_all().to_pylist()
    tables = {
        (catalog["catalog_name"], schema["db_schema_name"], table["table_name"]): table
        for catalog in objects
        for schema in catalog["catalog_db_schemas"] or []
        for table in schema["db_schema_tables"] or []
    }
    key = next(k for k in tables if k[2] == "fruit")
    columns = [c["column_name"] for c in tables[key]["table_columns"]]
    assert columns == ["id", "name", "price"]


def test_get_table_schema(alice):
    schema = alice.adbc_get_table_schema("fruit", db_schema_filter="main")
    assert schema.names == ["id", "name", "price"]
    assert str(schema.field("id").type) == "int32"


def test_get_table_types(alice):
    assert len(alice.adbc_get_table_types()) > 0


# ---------- routing and security ----------

def test_users_only_see_their_own_database(alice):
    with connect(BOB) as bob:
        with pytest.raises(adbc_driver_manager.Error):
            query(bob, "SELECT * FROM fruit")
    with connect(ADMIN) as admin:
        users = query(admin, "SELECT username FROM opencanard_users ORDER BY 1").column("username").to_pylist()
        assert "alice" in users and "bob" in users
    with pytest.raises(adbc_driver_manager.Error):
        query(alice, "SELECT * FROM opencanard_users")


def test_wrong_password_is_refused():
    with pytest.raises(adbc_driver_manager.Error):
        with connect(("alice", "wrong")) as conn:
            query(conn, "SELECT 1")


def test_sql_errors_are_reported(alice):
    with pytest.raises(adbc_driver_manager.ProgrammingError) as error:
        query(alice, "SELECT * FROM no_such_table")
    assert "no_such_table" in str(error.value)


# ---------- OpenCanard's own protocol still works ----------

def basic(user):
    token = base64.b64encode(f"{user[0]}:{user[1]}".encode()).decode()
    return flight.FlightCallOptions(headers=[(b"authorization", f"Basic {token}".encode())])


def test_raw_sql_descriptor_still_works():
    client = flight.FlightClient(URI)
    options = basic(ALICE)
    info = client.get_flight_info(flight.FlightDescriptor.for_command(b"SELECT 7 AS seven"), options)
    table = client.do_get(info.endpoints[0].ticket, options).read_all()
    assert table.column("seven").to_pylist() == [7]


def test_basic_login_issues_a_bearer_token_that_works():
    client = flight.FlightClient(URI)
    header = client.authenticate_basic_token(*ALICE)
    assert header[1].startswith(b"Bearer ")
    options = flight.FlightCallOptions(headers=[header])
    info = client.get_flight_info(flight.FlightDescriptor.for_command(b"SELECT 8 AS eight"), options)
    assert client.do_get(info.endpoints[0].ticket, options).read_all().column("eight").to_pylist() == [8]


def test_made_up_bearer_token_is_refused():
    client = flight.FlightClient(URI)
    options = flight.FlightCallOptions(headers=[(b"authorization", b"Bearer not-a-real-token")])
    with pytest.raises(flight.FlightUnauthenticatedError):
        client.get_flight_info(flight.FlightDescriptor.for_command(b"SELECT 1"), options)
