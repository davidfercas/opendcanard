#!/usr/bin/env bash
# Starts a throwaway OpenCanard server from the built distribution, creates two users and runs the Flight SQL
# integration tests against it: ADBC (Python), the Apache Arrow Flight SQL JDBC driver, and OpenCanard's own JDBC
# driver (to check the original protocol still works).
#
# Needs: `mvn package` done, Java 17+, Python 3 with
#   pip install adbc-driver-flightsql adbc-driver-manager pyarrow pytest
# Ports 8815 (Flight) and 8084 (REST) must be free.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
START_SH="$(find "$ROOT/opencanard-server/target" -path '*/bin/start.sh' | head -1)"
if [ -z "$START_SH" ]; then
	echo "No server distribution found: run 'mvn package' first" >&2
	exit 1
fi
DIST="$(dirname "$(dirname "$START_SH")")"

WORK="$(mktemp -d)"
cp -r "$DIST" "$WORK/opencanard"
SERVER="$WORK/opencanard"
mkdir -p "$SERVER/logs"
echo "Server copied to $SERVER"

export OPENCANARD_ADMIN_USER=admin
export OPENCANARD_ADMIN_PASSWORD=admin-pw
chmod +x "$SERVER/bin/start.sh"
"$SERVER/bin/start.sh" >"$SERVER/logs/stdout.log" 2>&1 &
SERVER_PID=$!
cleanup() {
	kill "$SERVER_PID" 2>/dev/null || true
	wait "$SERVER_PID" 2>/dev/null || true
	echo "Server log: $SERVER/logs"
}
trap cleanup EXIT

REST=http://localhost:8084
for _ in $(seq 1 90); do
	if curl -sf -o /dev/null -X POST "$REST/api/login" -H 'Content-Type: application/json' \
		-d '{"username":"admin","password":"admin-pw"}'; then
		break
	fi
	if ! kill -0 "$SERVER_PID" 2>/dev/null; then
		echo "The server stopped while starting:" >&2
		tail -50 "$SERVER/logs/stdout.log" >&2
		exit 1
	fi
	sleep 1
done
echo "Server is up"

for user in alice bob; do
	curl -sf -X POST "$REST/management/users" -u admin:admin-pw -H 'Content-Type: application/json' \
		-d "{\"username\":\"$user\",\"password\":\"$user-pw\",\"role\":\"user\"}"
	echo
done

echo "== Users and roles (REST)"
expect() { # expect <http status> <curl args...>
	local want=$1 got
	shift
	got="$(curl -s -o /tmp/oc-body -w '%{http_code}' "$@")"
	if [ "$got" != "$want" ]; then
		echo "Expected HTTP $want, got $got for: $*" >&2
		cat /tmp/oc-body >&2
		exit 1
	fi
}
JSON=(-H 'Content-Type: application/json')
ADM=(-u admin:admin-pw)
expect 201 -X POST "$REST/management/roles" "${ADM[@]}" "${JSON[@]}" -d '{"name":"analyst","description":"Reports"}'
expect 409 -X POST "$REST/management/roles" "${ADM[@]}" "${JSON[@]}" -d '{"name":"analyst"}'
expect 200 -X PUT "$REST/management/users/bob/roles" "${ADM[@]}" "${JSON[@]}" -d '{"roles":["user","analyst"]}'
expect 200 -X PUT "$REST/management/roles/analyst" "${ADM[@]}" "${JSON[@]}" -d '{"name":"analysts","description":"Reports"}'
expect 200 "$REST/management/users" "${ADM[@]}"
grep -q '"analysts"' /tmp/oc-body
expect 400 -X DELETE "$REST/management/roles/admin" "${ADM[@]}"
expect 400 -X PUT "$REST/management/roles/user" "${ADM[@]}" "${JSON[@]}" -d '{"name":"members"}'
expect 400 -X PUT "$REST/management/users/admin/roles" "${ADM[@]}" "${JSON[@]}" -d '{"roles":["user"]}'
expect 400 -X DELETE "$REST/management/users/admin" "${ADM[@]}"
expect 403 "$REST/management/roles" -u bob:bob-pw
expect 403 -X DELETE "$REST/management/users/alice" -u bob:bob-pw
# only an admin can stop the server (#19)
expect 403 -X POST "$REST/management/shutdown" -u bob:bob-pw
expect 200 -X DELETE "$REST/management/roles/analysts" "${ADM[@]}"
# a second admin cannot delete the built-in admin user either
expect 201 -X POST "$REST/management/users" "${ADM[@]}" "${JSON[@]}" -d '{"username":"admin2","password":"admin2-pw","roles":["admin"]}'
expect 400 -X DELETE "$REST/management/users/admin" -u admin2:admin2-pw
expect 400 -X DELETE "$REST/management/users/admin2" -u admin2:admin2-pw
test -n "$(find "$SERVER" -name admin2.duckdb)"
expect 200 -X DELETE "$REST/management/users/admin2" "${ADM[@]}"
test -z "$(find "$SERVER" -name admin2.duckdb)"
expect 401 "$REST/management/roles" -u admin2:admin2-pw
echo "Users and roles checks passed"

echo "== JWT signing key (#12)"
test -f "$SERVER/metadata/jwt.key"
if [ "$(uname)" != "Darwin" ]; then test "$(stat -c %a "$SERVER/metadata/jwt.key")" = 600; fi
TOKEN="$(curl -sf -X POST "$REST/api/login" "${JSON[@]}" -d '{"username":"admin","password":"admin-pw"}' |
	python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
expect 200 "$REST/management/users" -H "Authorization: Bearer $TOKEN"
# a well-formed admin token signed with the secret older versions had in their source must be refused
FORGED="$(python3 - <<'PY'
import base64, hashlib, hmac, json, time
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
now = int(time.time())
head = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
body = b64(json.dumps({"iss": "opencanard", "sub": "admin", "username": "admin", "roles": ["admin"],
                       "iat": now, "exp": now + 3600}).encode())
sig = hmac.new(b"your-super-secret-key-change-this", f"{head}.{body}".encode(), hashlib.sha256).digest()
print(f"{head}.{body}.{b64(sig)}")
PY
)"
expect 401 "$REST/management/users" -H "Authorization: Bearer $FORGED"
echo "JWT checks passed"

echo "== ADBC (Python)"
# the Python tests sign their own JWTs with the key the server generated
export OC_JWT_SECRET="$(cat "$SERVER/metadata/jwt.key")"
python3 -m pytest -q "$HERE/test_flight_sql.py"

echo "== Apache Arrow Flight SQL JDBC driver"
java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "$SERVER/lib/external/*" \
	"$HERE/FlightSqlJdbcCheck.java" localhost:8815 bob bob-pw

echo "== OpenCanard JDBC driver (original protocol)"
DRIVER_JAR="$(ls "$ROOT"/opencanard-jdbc-driver/target/opencanard-jdbc-driver-*-all.jar | head -1)"
OUT="$(java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "$DRIVER_JAR" io.opencanard.driver.jdbc.OpenCanardJdbcClient \
	jdbc:opencanard://localhost:8815 alice alice-pw "SELECT 4242 AS legacy" 2>&1)"
echo "$OUT"
grep -q 4242 <<<"$OUT"
java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "$DRIVER_JAR" "$HERE/OpenCanardJdbcCheck.java" \
	jdbc:opencanard://localhost:8815 alice alice-pw

echo "All Flight SQL integration tests passed"
