#!/usr/bin/env python3
"""Runs ``SELECT 1`` against an OpenCanard server with each Python client.

Clients:
  adbc    Arrow Flight SQL through the ADBC driver (recommended).
  flight  OpenCanard's own protocol through plain PyArrow Flight.

Usage:
  pip install -r requirements.txt
  python select_one.py                       # runs every client
  python select_one.py --client adbc --host db.example --user david --password s3cret

The password can also come from the OPENCANARD_PASSWORD environment variable.
"""

import argparse
import base64
import os
import sys

SQL = "SELECT 1"


def run_adbc(uri: str, user: str, password: str):
    import adbc_driver_flightsql.dbapi as flight_sql

    # OpenCanard has no transactions, so autocommit must stay on.
    with flight_sql.connect(
        uri, db_kwargs={"username": user, "password": password}, autocommit=True
    ) as conn:
        with conn.cursor() as cur:
            cur.execute(SQL)
            return cur.fetch_arrow_table()


def run_flight(uri: str, user: str, password: str):
    import pyarrow.flight as flight

    client = flight.FlightClient(uri)
    try:
        credentials = base64.b64encode(f"{user}:{password}".encode()).decode()
        options = flight.FlightCallOptions(
            headers=[(b"authorization", f"Basic {credentials}".encode())]
        )
        # The Flight command is the SQL text itself.
        descriptor = flight.FlightDescriptor.for_command(SQL.encode())
        info = client.get_flight_info(descriptor, options)
        return client.do_get(info.endpoints[0].ticket, options).read_all()
    finally:
        client.close()


CLIENTS = {"adbc": run_adbc, "flight": run_flight}


def main() -> int:
    parser = argparse.ArgumentParser(description=f"Run '{SQL}' against OpenCanard.")
    parser.add_argument("--client", choices=[*CLIENTS, "all"], default="all")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8815)
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password", default=os.environ.get("OPENCANARD_PASSWORD", "change-me"))
    args = parser.parse_args()

    uri = f"grpc://{args.host}:{args.port}"
    names = list(CLIENTS) if args.client == "all" else [args.client]

    failed = 0
    for name in names:
        try:
            table = CLIENTS[name](uri, args.user, args.password)
            value = table.column(0)[0].as_py()
            print(f"[{name}] {SQL} -> {value}")
        except Exception as exc:  # report and keep going with the next client
            failed += 1
            print(f"[{name}] failed: {exc}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
