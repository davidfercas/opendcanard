# Web console

OpenCanard serves a browser console on the REST port: the structure of your database on the left, a SQL
editor and the results on the right.

Open `http://localhost:8084/opencanard-console/` (`/` redirects there) and sign in with an OpenCanard user.

## What you can do

- **Browse the structure.** Databases, schemas, tables, views and indexes. Open a table to see its columns,
  types and primary key. `information_schema` and `pg_catalog` are listed in grey. The filter box narrows
  tables, views and indexes by name; the reload button re-reads the structure.
- **Run SQL.** Type in the editor and press `Ctrl+Enter` (`Cmd+Enter` on macOS) or **Run**. If text is
  selected, only the selection runs. Several statements separated by `;` run in order and the result of the
  last one is shown; if one fails, the ones before it have already run.
- **Kill a query.** While a query runs, **Kill** appears next to **Run** and stops it. The result panel then
  says *Cancelled*. In a script, the statements that ran before the killed one are not undone, and the ones
  after it do not run.
- **See the running queries.** **Running queries** opens a list of the queries that have not finished yet,
  with who started them, from where (the console or Arrow Flight, which includes Python, JDBC and DBeaver),
  when, for how long and their SQL. It refreshes every 2 seconds and has a **Kill** button on each row.
  A normal user sees only their own queries; an **admin sees and can kill the queries of every user**.
- **Run a previous query.** **History** next to **Run** lists your last queries, newest first, with when
  they ran and how they ended (row count, message or *Failed*). Click one to put it in the editor, or its
  play button to run it straight away. Running the same SQL again moves it to the top instead of adding a
  copy. The list keeps `console.history.size` queries (10 by default, see below), is stored in this
  browser's `localStorage` per user name, and **Clear** empties it.
- **Open a table.** Double-click it, or use the play button that appears on the row, to run
  `SELECT * FROM schema.table LIMIT 100`.
- **Read the results.** Column types are shown under the names, `NULL` is distinct from empty text,
  numbers are right-aligned. Copy or download the result as CSV.
- **Change the structure.** After `CREATE`, `DROP`, `ALTER` or any multi-statement script the tree reloads.
- **Clean start.** Signing in or out empties every panel and closes any open dialog: no error, result, tree or S3 storage message from the previous
  session (or the previous user) stays on screen, and a query still running when you signed out is ignored
  when it finishes.
- **Layout.** Drag the dividers (or focus one and use the arrow keys) to resize the panels. The theme button
  switches between light and dark; the first choice follows the system.

## S3 storage

The lower half of the left panel lists the S3 storages of the signed-in user. Each user manages their own list,
admins included; nobody sees another user's storages. **Add storage** opens a form:

| Field | Meaning |
|-------|---------|
| Name | Your label, unique among your storages. It cannot be changed afterwards. |
| Bucket, Region | Where the data lives. Region is required for AWS S3. |
| Folder | Optional. Only this folder of the bucket is used. |
| Access key ID, Secret access key | Optional. The credentials; the secret is stored encrypted and is never shown again. Leave both empty for a public bucket: its requests are sent unsigned. |
| Compatible services | Endpoint (`host:port`, no `http://`), URL style and https, for MinIO, Ceph or similar. Leave empty for AWS. |

Use the pencil to edit a storage (leave the secret empty to keep the current one, or empty both fields to make it
a public bucket) and the bin to remove it.
Removing a storage deletes its saved keys only, never the files in the bucket.

### Browsing a bucket and testing a connection

Click a storage to open its bucket: folders and files appear under it, 200 per page (**Load more** gets the
next ones). Files show their size; hover for the full `s3://` path and the date. The play button on a `.parquet`,
`.csv`, `.tsv`, `.json`, `.jsonl` or `.ndjson` file (also with `.gz` or `.zst`) writes and runs a `SELECT` for it.
The reload button on each storage (next to edit and remove) lists its bucket again, including the folders you
had open.

**Test connection** in the form tries the values you typed without saving them and says what S3 answered, for
example `SignatureDoesNotMatch (check the secret access key and the region)`, `AccessDenied` or `NoSuchBucket`.
With the secret field empty on an existing storage, the saved secret is used. With both fields empty the bucket is
read anonymously; a bucket that does not allow public listing answers `AccessDenied`.

Listing needs the `s3:ListBucket` permission, which reading a known file does not. A key that can read files
but not list the bucket works in queries and shows `AccessDenied` in the tree. The server lists buckets itself
(a signed `ListObjectsV2` call), so this does not need DuckDB's `httpfs` extension, but the server must be able to
reach the endpoint. The server contacts whatever endpoint a user types, so on shared servers restrict outgoing
network access if that is a concern.

### Reading files from a storage

When you save, edit or remove a storage, and again every time you run a query or a JDBC or Flight client connects,
your storages are registered in DuckDB as temporary S3 secrets whose scope is `s3://bucket/folder/`. A path inside that scope uses the
endpoint, region, URL style and credentials of the storage:

```sql
SELECT * FROM read_csv_auto('s3://test/countries/countries.csv', all_varchar = true);
```

- You can have several storages. Each one is its own secret and the longest matching scope wins, so a folder
  storage can sit inside a whole-bucket one. Two storages cannot share the same bucket and folder, because a path
  could not tell them apart; the form refuses it.
- The path must start with the bucket (and the folder, if you set one). Anything outside the scope falls back to
  DuckDB's defaults, which means AWS, and fails with HTTP 403 for a bucket on MinIO.
- The first use installs the DuckDB `httpfs` extension, so the server needs internet access once. Without it
  the query fails with a message naming the extension.
- If DuckDB cannot register a saved storage (for example, `httpfs` cannot be downloaded), the storage is still
  saved and a red notice above the list gives the reason.
- Changes to a storage apply to the next query. DuckDB keeps secrets per database, so they are visible to
  every connection to that database: your own file, except for admins, who share the metadata database.
- If the storage key changed and a secret cannot be decrypted, that storage is skipped and logged;
  edit it and enter the secret again.

## History size

`console.history.size` in `conf/opencanard.properties` sets how many past queries the console keeps for each
user. Default `10`, at most `100`; `0` hides **History**. The value reaches the page through
`GET /api/session` (`historySize`), so a restart of the server is enough to change it.

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
- Killing interrupts DuckDB, which stops at its next check. That is almost immediate for scans and joins,
  but a slow S3 request already in progress can finish first.
- Arrow Flight runs each `SELECT` twice (once to get the schema, once to stream it), so a Flight query can
  show up twice in **Running queries**, one after the other.
- The session is a JWT valid for one hour, kept in the tab's `sessionStorage`. After it expires the
  console asks you to sign in again.

## API behind the console

All calls need `Authorization: Bearer <token>` (or Basic credentials) and appear in Swagger under **Console**.

| Method and path | Purpose |
|-----------------|---------|
| `GET /api/session` | login, whether it is an admin, which database, `historySize` |
| `GET /api/catalog` | databases, schemas, tables, views, indexes |
| `GET /api/columns?catalog=&schema=&table=` | columns of a table or view |
| `POST /api/query` | body `{"sql": "...", "maxRows": 1000, "queryId": "..."}`; `queryId` is optional (8 to 64 letters, digits or dashes) and lets the browser kill the query before the answer arrives |
| `GET /api/queries` | running queries: `{"allUsers": false, "queries": [{"id", "user", "source", "sql", "startedAt", "elapsedMs", "state"}]}`; `state` is `running` or `cancelling`. Admins get every user's queries |
| `POST /api/queries/{id}/cancel` | kill a query: 202 when sent, 404 when it is not running any more or belongs to another user (admins can kill any) |
| `GET /api/storages` | your S3 storages (never the secret) |
| `POST /api/storages` | add one |
| `PUT /api/storages/{name}` | change one; an empty `secretAccessKey` keeps the stored secret |
| `DELETE /api/storages/{name}` | remove one |
| `GET /api/storages/{name}/objects?path=&token=` | folders and files of the bucket, one level |
| `POST /api/storages/test` | try the values of a storage without saving; answers `{"ok": true, "message": "..."}` |

`POST /api/query` answers:

```json
{ "columns": [{"name": "id", "type": "INTEGER"}], "rows": [[1]], "rowCount": 1, "truncated": false,
  "updateCount": -1, "statements": 1, "message": "1 row", "elapsedMs": 3 }
```

SQL errors return HTTP 400 with `{"error": "..."}`; a killed query returns 400 with
`{"error": "The query was cancelled.", "cancelled": true}`; a user without a database gets 403. Arrow Flight
clients get the status `CANCELLED` for a killed query.

A storage looks like this in responses (and in the body of POST and PUT, plus `secretAccessKey`):

```json
{ "name": "data-lake", "bucket": "company-data", "region": "eu-west-1", "endpoint": null, "urlStyle": "vhost",
  "useSsl": true, "pathPrefix": "raw/2026/", "accessKeyId": "AKIA...", "hasSecret": true, "anonymous": false,
  "url": "s3://company-data/raw/2026/", "createdAt": "2026-11-04 10:00:00", "updatedAt": "2026-11-04 10:00:00" }
```

Storage calls answer 400 for an invalid field (the message says which), 404 for an unknown name and 409 for a
name already in use.

## Files

The page is plain HTML, CSS and JavaScript in `opencanard-server/src/main/resources/web/`, packaged in the
server jar. It loads nothing from the internet (the IBM Plex fonts are bundled), so it works on a
closed network.
