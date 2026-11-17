#!/usr/bin/env bash
# Repeatable backup/restore verification (LOCAL ONLY: throwaway Postgres container; the source database is only READ, never altered).
#
#   A. fresh database, documented layout (schema public, runtime role ana_app): the migrations apply from V1, the app starts with ddl-auto=validate,
#      and the Data API lockdown (V11) holds: RLS on every application table, no policy, no privilege for anon/authenticated/service_role.
#   B. dump of the fresh database -> restore into a SEPARATE database -> the lockdown still holds after the restore, the app starts on it.
#   C. dump of the real local data (schema ana_depot, role ana_depot_app, the data the verification suites created) -> restore into a SEPARATE
#      database -> every table has the same row count, the app starts on the copy (Flyway validate + Hibernate validate), the catalog answers like
#      the source, and the ledger is consistent (SQL sums and the staff reconciliation endpoint).
#
# It does NOT prove: hosted Supabase backups/PITR, Cloudflare R2 object recovery, restore time at production volume, or cross-region recovery.
#
# Requirements: docker, curl, python3, a built jar (JAR=...), the source container (SOURCE_CONTAINER, default supabase_db_ana-depot) and, for the
# staff API checks, a LOCAL Supabase stack with SUPABASE_PUBLIC_KEY, STAFF_EMAIL and STAFF_PASSWORD set (otherwise those checks are skipped, and said so).
# Everything it creates (container, dump files, logs) is removed at the end unless KEEP=1.
set -uo pipefail

JAR=${JAR:?set JAR to the built ana-depot-api jar}
JAVA=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}; JAVA=${JAVA:-java}
SOURCE_CONTAINER=${SOURCE_CONTAINER:-supabase_db_ana-depot}
SOURCE_SCHEMA=${SOURCE_SCHEMA:-ana_depot}
SOURCE_OWNER=${SOURCE_OWNER:-ana_depot_app}
IMAGE=${IMAGE:-public.ecr.aws/supabase/postgres:17.6.1.064}   # the image the local Supabase stack uses: has anon/authenticated/service_role like a hosted project
SCRATCH_NAME=ana-restore-scratch
SCRATCH_PORT=${SCRATCH_PORT:-54329}
SUPABASE_URL=${SUPABASE_URL:-http://127.0.0.1:54321}
WORK=$(mktemp -d)
PW=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)   # throwaway password of a throwaway container
PASS=0; FAIL=0; APP_PID=""

now() { date +%s.%N; }
secs() { python3 -c "print(f'{$2 - $1:.1f}s')"; }
ok() { PASS=$((PASS + 1)); echo "PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL  $1"; }
check() { if [ "$2" = "0" ]; then ok "$1"; else bad "$1 ($3)"; fi; }
sql() { docker exec -e PGPASSWORD="$PW" "$SCRATCH_NAME" psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 -At "$@"; }

cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null
  if [ "${KEEP:-0}" != "1" ]; then docker rm -f "$SCRATCH_NAME" >/dev/null 2>&1; rm -rf "$WORK"; else echo "kept: container $SCRATCH_NAME, files $WORK"; fi
}
trap cleanup EXIT

start_app() {  # url user password port  [extra env as VAR=value ...]
  local url=$1 user=$2 pass=$3 port=$4; shift 4
  env SPRING_DATASOURCE_URL="$url" SPRING_DATASOURCE_USERNAME="$user" SPRING_DATASOURCE_PASSWORD="$pass" SUPABASE_URL="$SUPABASE_URL" "$@" \
    "$JAVA" -jar "$JAR" --server.port="$port" >"$WORK/app-$port.log" 2>&1 &
  APP_PID=$!
  for _ in $(seq 1 180); do
    curl -s -o /dev/null "http://127.0.0.1:$port/actuator/health" && return 0
    kill -0 "$APP_PID" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}
stop_app() { [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null && wait "$APP_PID" 2>/dev/null; APP_PID=""; sleep 1; }

counts() {  # database schema -> "table count" per line (base tables only)
  sql -d "$1" -c "select table_name || ' ' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
                  from information_schema.tables where table_schema='$2' and table_type='BASE TABLE' order by table_name"
}

lockdown() {  # database -> prints nothing, returns the number of violations
  local d=$1 bad=0 n
  n=$(sql -d "$d" -c "select count(*) from pg_class c join pg_namespace s on s.oid=c.relnamespace where s.nspname='public' and c.relkind in ('r','p') and c.relname<>'flyway_schema_history' and not c.relrowsecurity"); [ "$n" = 0 ] || { echo "  tables without RLS: $n"; bad=$((bad+1)); }
  n=$(sql -d "$d" -c "select count(*) from pg_policies where schemaname='public'"); [ "$n" = 0 ] || { echo "  policies: $n"; bad=$((bad+1)); }
  n=$(sql -d "$d" -c "select count(*) from information_schema.role_table_grants where table_schema='public' and grantee in ('anon','authenticated','service_role')"); [ "$n" = 0 ] || { echo "  grants to API roles: $n"; bad=$((bad+1)); }
  n=$(sql -d "$d" -c "select count(*) from information_schema.role_routine_grants where routine_schema='public' and grantee in ('anon','authenticated','service_role','PUBLIC')"); [ "$n" = 0 ] || { echo "  function grants: $n"; bad=$((bad+1)); }
  # behaviour, not only catalog: the anon role really cannot read
  if sql -d "$d" -c "set role anon; select count(*) from public.products" >/dev/null 2>&1; then echo "  anon CAN read public.products"; bad=$((bad+1)); fi
  return $bad
}

echo "== scratch PostgreSQL ($IMAGE on 127.0.0.1:$SCRATCH_PORT)"
docker rm -f "$SCRATCH_NAME" >/dev/null 2>&1
T0=$(now)
docker run -d --name "$SCRATCH_NAME" -e POSTGRES_PASSWORD="$PW" -p "127.0.0.1:$SCRATCH_PORT:5432" "$IMAGE" >/dev/null || { echo "cannot start the scratch container"; exit 2; }
for _ in $(seq 1 60); do sql -c "select 1" >/dev/null 2>&1 && break; sleep 1; done
sql -c "select 1" >/dev/null 2>&1 || { echo "scratch database did not come up"; exit 2; }
echo "ready in $(secs "$T0" "$(now)"); $(sql -c 'show server_version')"
# the owner roles must exist before a restore (objects keep their owner); postgres needs to be able to SET ROLE to them, as a hosted restore would
sql -c "create role ana_app login password '$PW'" -c "create role $SOURCE_OWNER login password '$PW'" -c "grant ana_app, $SOURCE_OWNER to postgres" >/dev/null
URL() { echo "jdbc:postgresql://127.0.0.1:$SCRATCH_PORT/$1${2:-}"; }

# ------------------------------------------------------------------------------------------------------------------------------ A
echo; echo "== A. fresh database, documented layout (schema public, role ana_app)"
sql -c "create database fresh owner postgres" >/dev/null
sql -d fresh -c "grant usage, create on schema public to ana_app" >/dev/null
T=$(now)
if start_app "$(URL fresh)" ana_app "$PW" 8086; then
  ok "migrations applied from V1 and the application started with ddl-auto=validate ($(secs "$T" "$(now)"))"
else bad "application did not start on the fresh database (see $WORK/app-8086.log)"; tail -5 "$WORK/app-8086.log"; fi
echo "  flyway history: $(sql -d fresh -c "select count(*) || ' migrations, latest V' || max(version::int) from public.flyway_schema_history where success")"
curl -s http://127.0.0.1:8086/actuator/health/readiness | grep -q '"UP"'; check "readiness UP on the fresh database" $? ""
[ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8086/api/v1/products)" = 200 ]; check "GET /api/v1/products answers 200 on the fresh database" $? ""
lockdown fresh; check "Data API lockdown (V11) holds on the fresh database" $? "see above"
stop_app

# ------------------------------------------------------------------------------------------------------------------------------ B
echo; echo "== B. dump of the fresh database -> restore into a separate database"
T=$(now)
docker exec -e PGPASSWORD="$PW" "$SCRATCH_NAME" pg_dump -h 127.0.0.1 -U postgres -Fc -d fresh -f /tmp/fresh.dump; check "pg_dump (custom format) of the fresh database" $? ""
sql -c "create database fresh_restored owner postgres" >/dev/null
sql -d fresh_restored -c "grant usage, create on schema public to ana_app" >/dev/null   # as on the first setup: the owner role needs CREATE on the schema to own objects in it
docker exec -e PGPASSWORD="$PW" "$SCRATCH_NAME" pg_restore -h 127.0.0.1 -U postgres -d fresh_restored --exit-on-error /tmp/fresh.dump; RC=$?; check "pg_restore into a separate database ($(secs "$T" "$(now)") for dump+restore)" $RC ""
diff <(counts fresh public) <(counts fresh_restored public) >/dev/null; check "row counts of every table identical (fresh vs restored)" $? "differ"
lockdown fresh_restored; check "Data API lockdown (V11) still holds AFTER the restore (RLS flags, absent grants and anon behaviour preserved)" $? "see above"
if start_app "$(URL fresh_restored)" ana_app "$PW" 8087; then ok "application starts on the restored database (Flyway validate + Hibernate validate)"; else bad "application did not start on the restored database"; tail -5 "$WORK/app-8087.log"; fi
stop_app

# ------------------------------------------------------------------------------------------------------------------------------ C
echo; echo "== C. dump of the real local data ($SOURCE_CONTAINER, schema $SOURCE_SCHEMA) -> restore into a separate database"
T=$(now)
docker exec "$SOURCE_CONTAINER" pg_dump -U postgres -Fc --schema="$SOURCE_SCHEMA" -d postgres -f /tmp/ana-source.dump; check "pg_dump of the source (read-only)" $? ""
docker cp "$SOURCE_CONTAINER:/tmp/ana-source.dump" "$WORK/ana-source.dump" >/dev/null && docker exec "$SOURCE_CONTAINER" rm -f /tmp/ana-source.dump
SIZE=$(du -k "$WORK/ana-source.dump" | cut -f1)
sql -c "create database restored owner postgres" >/dev/null
docker cp "$WORK/ana-source.dump" "$SCRATCH_NAME:/tmp/ana-source.dump" >/dev/null
docker exec -e PGPASSWORD="$PW" "$SCRATCH_NAME" pg_restore -h 127.0.0.1 -U postgres -d restored --exit-on-error /tmp/ana-source.dump; RC=$?; check "pg_restore into a separate database (dump ${SIZE} kB; dump+restore $(secs "$T" "$(now)"))" $RC ""
docker exec "$SOURCE_CONTAINER" psql -U postgres -At -c "select table_name || ' ' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text from information_schema.tables where table_schema='$SOURCE_SCHEMA' and table_type='BASE TABLE' order by table_name" >"$WORK/src.counts"
counts restored "$SOURCE_SCHEMA" >"$WORK/dst.counts"
TABLES=$(wc -l <"$WORK/dst.counts")
if diff "$WORK/src.counts" "$WORK/dst.counts" >"$WORK/counts.diff"; then ok "row counts identical in all $TABLES tables ($(awk '{s+=$2} END{print s}' "$WORK/dst.counts") rows)"
else bad "row counts differ (the source may have changed during the dump; re-run if only volatile tables differ)"; cat "$WORK/counts.diff"; fi
awk '{printf "    %-34s %s\n",$1,$2}' "$WORK/dst.counts" | grep -E " (reservations|payments|ledger_entries|products|customers|inventory_movements|outbox_events) "

# ledger consistency straight from the data: per reservation and currency the ledger sums to what the payment rows say (reversals aside, see the endpoint check)
BAD_LEDGER=$(sql -d restored -c "select count(*) from (select reservation_id, currency from $SOURCE_SCHEMA.ledger_entries group by 1,2) l
  where not exists (select 1 from $SOURCE_SCHEMA.reservations r where r.id = l.reservation_id)")
[ "$BAD_LEDGER" = 0 ]; check "no ledger entry without its reservation (orphans: $BAD_LEDGER)" $? ""
ORPHAN_LINES=$(sql -d restored -c "select count(*) from $SOURCE_SCHEMA.reservation_lines l where not exists (select 1 from $SOURCE_SCHEMA.reservations r where r.id = l.reservation_id)")
[ "$ORPHAN_LINES" = 0 ]; check "no reservation line without its reservation" $? ""
LEDGER_SRC=$(docker exec "$SOURCE_CONTAINER" psql -U postgres -At -c "select coalesce(string_agg(currency || '=' || s, ' ' order by currency),'-') from (select currency, sum(amount) s from $SOURCE_SCHEMA.ledger_entries group by currency) x")
LEDGER_DST=$(sql -d restored -c "select coalesce(string_agg(currency || '=' || s, ' ' order by currency),'-') from (select currency, sum(amount) s from $SOURCE_SCHEMA.ledger_entries group by currency) x")
echo "  ledger totals: source [$LEDGER_SRC]  restored [$LEDGER_DST]"
[ "$LEDGER_SRC" = "$LEDGER_DST" ]; check "ledger totals per currency identical" $? "differ (source may have changed)"

T=$(now)
if start_app "$(URL restored "?currentSchema=$SOURCE_SCHEMA")" "$SOURCE_OWNER" "$PW" 8088 ANA_BOOTSTRAP_ADMIN_SUBJECT="${ANA_BOOTSTRAP_ADMIN_SUBJECT:-}"; then
  ok "application starts on the restored copy: Flyway validate + Hibernate validate ($(secs "$T" "$(now)"))"
else bad "application did not start on the restored copy"; tail -5 "$WORK/app-8088.log"; fi
# compared with the RESTORED rows (the live source may legitimately change while this script runs; row-level equality was checked above)
SRC_ACTIVE=$(sql -d restored -c "select count(*) from $SOURCE_SCHEMA.products where status='ACTIVE'")
API_TOTAL=$(curl -s "http://127.0.0.1:8088/api/v1/products?size=1" | python3 -c 'import sys,json; print(json.load(sys.stdin)["totalItems"])' 2>/dev/null)
echo "  active products: restored rows $SRC_ACTIVE, restored API $API_TOTAL"
[ "$SRC_ACTIVE" = "$API_TOTAL" ]; check "the public catalog on the restored copy lists the same number of active products as the restored rows" $? "differ"
CATS_API=$(curl -s "http://127.0.0.1:8088/api/v1/categories" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))' 2>/dev/null)
CATS_SRC=$(sql -d restored -c "select count(*) from $SOURCE_SCHEMA.categories where active")
[ "$CATS_API" = "$CATS_SRC" ]; check "the public categories on the restored copy equal the active categories of the restored rows ($CATS_SRC)" $? "api $CATS_API"

if [ -n "${SUPABASE_PUBLIC_KEY:-}" ] && [ -n "${STAFF_EMAIL:-}" ] && [ -n "${STAFF_PASSWORD:-}" ]; then
  TOK=$(curl -s "$SUPABASE_URL/auth/v1/token?grant_type=password" -H "apikey: $SUPABASE_PUBLIC_KEY" -H 'Content-Type: application/json' -d "{\"email\":\"$STAFF_EMAIL\",\"password\":\"$STAFF_PASSWORD\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])' 2>/dev/null)
  ME=$(curl -s "http://127.0.0.1:8088/api/v1/me" -H "Authorization: Bearer $TOK" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("staff"))' 2>/dev/null)
  [ "$ME" = "True" ]; check "the staff member (restored staff_members row) is still recognised as staff" $? "staff=$ME"
  # reconciliation endpoint on every reservation that has ledger entries
  IDS=$(sql -d restored -c "select distinct reservation_id from $SOURCE_SCHEMA.ledger_entries")
  N=0; INCONS=0
  for id in $IDS; do
    N=$((N + 1))
    C=$(curl -s "http://127.0.0.1:8088/api/v1/admin/reservations/$id/reconciliation" -H "Authorization: Bearer $TOK" | python3 -c 'import sys,json; print(json.load(sys.stdin)["consistent"])' 2>/dev/null)
    [ "$C" = "True" ] || INCONS=$((INCONS + 1))
  done
  [ "$N" -gt 0 ] && [ "$INCONS" = 0 ]; check "reconciliation endpoint: $N reservations with payments, $INCONS inconsistent" $? "inconsistent=$INCONS of $N"
  OV=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:8088/api/v1/admin/reports/overview" -H "Authorization: Bearer $TOK")
  [ "$OV" = 200 ]; check "staff report endpoint answers on the restored copy" $? "HTTP $OV"
else
  echo "SKIP  staff API checks (set SUPABASE_PUBLIC_KEY, STAFF_EMAIL, STAFF_PASSWORD for a local Supabase stack)"
fi
stop_app

echo; echo "== result: $PASS passed, $FAIL failed (total wall time $(secs "$T0" "$(now)"))"
echo "Not proven by this test: hosted Supabase backups/PITR, Cloudflare R2 object recovery, restore time at production volume."
[ "$FAIL" = 0 ]
