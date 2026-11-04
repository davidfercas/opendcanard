# Web console

OpenCanard serves a browser console on the REST port: the structure of your database on the left, a SQL
editor and the results on the right.

Open `http://localhost:8084/console/` (`/` redirects there) and sign in with an OpenCanard user.

## What you can do

- **Browse the structure.** Databases, schemas, tables, views and indexes. Open a table to see its columns,
  types and primary key. `information_schema` and `pg_catalog` are listed in grey. The filter box narrows
  tables, views and indexes by name; the reload button re-reads the structure.
- **Run SQL.** Type in the editor and press `Ctrl+Enter` (`Cmd+Enter` on macOS) or **Run**. If text is
  selected, only the selection runs. Several statements separated by `;` run in order and the result of the
  last one is shown; if one fails, the ones before it have already run.
- **Open a table.** Double-click it, or use the play button that appears on the row, to run
  `SELECT * FROM schema.table LIMIT 100`.
- **Read the results.** Column types are shown under the names, `NULL` is distinct from empty text,
  numbers are right-aligned. Copy or download the result as CSV.
- **Change the structure.** After `CREATE`, `DROP`, `ALTER` or any multi-statement script the tree reloads.
- **Layout.** Drag the dividers (or focus one and use the arrow keys) to resize the panels. The theme button
  switches between light and dark; the first choice follows the system.

## Which database you work on

The same rule as Arrow Flight and the JDBC driver:

| User | Database |
|------|----------|
| role `admin` | the metadata database (`metadata.duckdb`) |
| any other user | the file registered for the login in `opencanard_databases` |
| no registered file | refused: "No database is registered for '...'" |

An admin can therefore read and write `opencanard_users`, which holds the password hashes. Create a regular
user for everyday queries.

## Limits

- At most 1,000 rows come back by default. The **Row limit** list raises it to 100,000. Longer text is cut
  at 100,000 characters per cell; `BIGINT` values beyond 2^53 and `DECIMAL` values are sent as text so no digit is lost.
- A query cannot be cancelled once started.
- The session is a JWT valid for one hour, kept in the tab's `sessionStorage`. After it expires the
  console asks you to sign in again.

## API behind the console

All calls need `Authorization: Bearer <token>` (or Basic credentials) and appear in Swagger under **Console**.

| Method and path | Purpose |
|-----------------|---------|
| `GET /api/console/session` | login, whether it is an admin, which database |
| `GET /api/console/catalog` | databases, schemas, tables, views, indexes |
| `GET /api/console/columns?catalog=&schema=&table=` | columns of a table or view |
| `POST /api/console/query` | body `{"sql": "...", "maxRows": 1000}` |

`POST /api/console/query` answers:

```json
{ "columns": [{"name": "id", "type": "INTEGER"}], "rows": [[1]], "rowCount": 1, "truncated": false,
  "updateCount": -1, "statements": 1, "message": "1 row", "elapsedMs": 3 }
```

SQL errors return HTTP 400 with `{"error": "..."}`; a user without a database gets 403.

## Files

The page is plain HTML, CSS and JavaScript in `opencanard-server/src/main/resources/web/`, packaged in the
server jar. It loads nothing from the internet (the IBM Plex fonts are bundled), so it works on a
closed network.
