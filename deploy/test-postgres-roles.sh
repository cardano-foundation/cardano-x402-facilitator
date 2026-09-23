#!/bin/sh
# Disposable integration gate for the real PostgreSQL bootstrap and upgrade scripts.
set -eu
root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fresh_name="x402-role-fresh-$$"
upgrade_name="x402-role-upgrade-$$"
cleanup() {
  docker rm -f "$fresh_name" "$upgrade_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM
await_ready() {
  name=$1
  attempt=0
  # The image briefly runs an init-only server, then stops it before its final
  # startup. Do not mistake that temporary server for a ready test database.
  until docker logs "$name" 2>&1 | grep -q 'PostgreSQL init process complete' \
      && docker exec -u postgres "$name" pg_isready -U postgres -d postgres >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 40 ]; then docker logs "$name"; exit 1; fi
    sleep 1
  done
}
apply_sql() {
  name=$1
  role=$2
  file=$3
  docker exec -i -u postgres "$name" psql -X -q -v ON_ERROR_STOP=1 -U "$role" -d postgres < "$file"
}
assert_roles() {
  name=$1
  actual=$(docker exec -u postgres "$name" psql -X -U postgres -d postgres -Atc \
    "SELECT count(*) FROM pg_roles WHERE rolname IN ('facilitator','yaci_store')
      AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolreplication OR rolbypassrls)")
  [ "$actual" = 2 ]
  hashes=$(docker exec -u postgres "$name" psql -X -U postgres -d postgres -Atc \
    "SELECT count(*) FROM pg_authid WHERE rolname IN ('postgres','facilitator','yaci_store')
      AND rolpassword LIKE 'SCRAM-SHA-256$%'")
  [ "$hashes" = 3 ]
  if docker exec -u postgres "$name" psql -X -v ON_ERROR_STOP=1 -U yaci_store -d postgres \
      -c 'SELECT count(*) FROM facilitator.settlement' >/dev/null 2>&1; then
    echo 'Yaci could read the facilitator journal' >&2; exit 1
  fi
}

docker run --name "$fresh_name" -e POSTGRES_PASSWORD=test-admin-only \
  -e POSTGRES_HOST_AUTH_METHOD=scram-sha-256 \
  -e POSTGRES_INITDB_ARGS='--auth-host=scram-sha-256 --auth-local=trust' \
  -e FACILITATOR_DB_PASSWORD=test-facilitator-only -e YACI_DB_PASSWORD=test-yaci-only \
  -v "$root_dir/deploy/postgres/10-roles.sh:/docker-entrypoint-initdb.d/10-roles.sh:ro" \
  -d postgres:17-alpine >/dev/null
await_ready "$fresh_name"
apply_sql "$fresh_name" facilitator "$root_dir/src/main/resources/db/migration/V1__settlement.sql"
apply_sql "$fresh_name" facilitator "$root_dir/src/main/resources/db/migration/V2__upstream_main_settlement.sql"
assert_roles "$fresh_name"
fresh_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$fresh_name")
for role_password in 'postgres:test-admin-only' 'facilitator:test-facilitator-only' 'yaci_store:test-yaci-only'; do
  role=${role_password%%:*}
  password=${role_password#*:}
  docker exec -e PGPASSWORD="$password" -u postgres "$fresh_name" \
    psql -X -h "$fresh_ip" -v ON_ERROR_STOP=1 -U "$role" -d postgres -Atc 'SELECT 1' >/dev/null
  if docker exec -e PGPASSWORD=wrong -u postgres "$fresh_name" \
    psql -X -h "$fresh_ip" -v ON_ERROR_STOP=1 -U "$role" -d postgres -Atc 'SELECT 1' >/dev/null 2>&1; then
    echo "wrong TCP password accepted for $role" >&2; exit 1
  fi
done

docker run --name "$upgrade_name" -e POSTGRES_PASSWORD=old-admin-only \
  -e POSTGRES_HOST_AUTH_METHOD=scram-sha-256 \
  -e POSTGRES_INITDB_ARGS='--auth-host=scram-sha-256 --auth-local=trust' \
  -e POSTGRES_ADMIN_PASSWORD=new-admin-only \
  -e FACILITATOR_DB_PASSWORD=test-facilitator-only -e YACI_DB_PASSWORD=test-yaci-only \
  -v "$root_dir/deploy/postgres/upgrade-existing.sh:/opt/x402/upgrade-existing.sh:ro" \
  -d postgres:17-alpine >/dev/null
await_ready "$upgrade_name"
apply_sql "$upgrade_name" postgres "$root_dir/src/main/resources/db/migration/V1__settlement.sql"
docker exec -u postgres "$upgrade_name" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres \
  -c "CREATE SCHEMA yaci_store; CREATE TABLE yaci_store.example (id bigint PRIMARY KEY);
      CREATE SEQUENCE yaci_store.example_seq; CREATE SCHEMA unrelated;
      CREATE TABLE unrelated.keep_me (id bigint PRIMARY KEY);
      CREATE TABLE facilitator.flyway_schema_history (installed_rank integer PRIMARY KEY, checksum integer);
      INSERT INTO facilitator.flyway_schema_history VALUES (1, 123456);
      INSERT INTO facilitator.settlement(tx_hash,attempt_id,requirements_digest,network,status,claimed_at)
      VALUES (repeat('a',64),'11111111-1111-1111-1111-111111111111',repeat('b',64),
      'cardano:preprod','SUBMITTED',now())" >/dev/null
docker exec -u postgres "$upgrade_name" sh /opt/x402/upgrade-existing.sh
apply_sql "$upgrade_name" facilitator "$root_dir/src/main/resources/db/migration/V2__upstream_main_settlement.sql"
preserved=$(docker exec -u postgres "$upgrade_name" psql -X -U facilitator -d postgres -Atc \
  "SELECT (SELECT checksum FROM facilitator.flyway_schema_history WHERE installed_rank=1)::text
   || '|' || (SELECT status FROM facilitator.settlement WHERE tx_hash=repeat('a',64))")
[ "$preserved" = '123456|SUBMITTED' ]
# Model a former administrator-owned V2 volume, then reapply the scoped upgrade.
docker exec -u postgres "$upgrade_name" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres \
  -c 'ALTER TABLE facilitator.settlement OWNER TO postgres;
      ALTER TABLE facilitator.flyway_schema_history OWNER TO postgres;
      ALTER SCHEMA facilitator OWNER TO postgres;
      ALTER TABLE yaci_store.example OWNER TO postgres;
      ALTER SEQUENCE yaci_store.example_seq OWNER TO postgres;
      ALTER SCHEMA yaci_store OWNER TO postgres' >/dev/null
docker exec -u postgres "$upgrade_name" sh /opt/x402/upgrade-existing.sh
assert_roles "$upgrade_name"
preserved=$(docker exec -u postgres "$upgrade_name" psql -X -U facilitator -d postgres -Atc \
  "SELECT (SELECT checksum FROM facilitator.flyway_schema_history WHERE installed_rank=1)::text
   || '|' || (SELECT status FROM facilitator.settlement WHERE tx_hash=repeat('a',64))")
[ "$preserved" = '123456|SUBMITTED' ]
owner=$(docker exec -u postgres "$upgrade_name" psql -X -U postgres -d postgres -Atc \
  "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='yaci_store.example'::regclass")
[ "$owner" = yaci_store ]
owner=$(docker exec -u postgres "$upgrade_name" psql -X -U postgres -d postgres -Atc \
  "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='unrelated.keep_me'::regclass")
[ "$owner" = postgres ]
upgrade_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$upgrade_name")
for role_password in 'postgres:new-admin-only' 'facilitator:test-facilitator-only' 'yaci_store:test-yaci-only'; do
  role=${role_password%%:*}
  password=${role_password#*:}
  docker exec -e PGPASSWORD="$password" -u postgres "$upgrade_name" \
    psql -X -h "$upgrade_ip" -v ON_ERROR_STOP=1 -U "$role" -d postgres -Atc 'SELECT 1' >/dev/null
  if docker exec -e PGPASSWORD=wrong -u postgres "$upgrade_name" \
    psql -X -h "$upgrade_ip" -v ON_ERROR_STOP=1 -U "$role" -d postgres -Atc 'SELECT 1' >/dev/null 2>&1; then
    echo "wrong upgraded TCP password accepted for $role" >&2; exit 1
  fi
done
# On-disk HBA mistakes are rejected before role or ownership mutation.
docker exec -u postgres "$upgrade_name" sh -c 'cp "$PGDATA/pg_hba.conf" "$PGDATA/pg_hba.conf.x402test"'
docker exec -u postgres "$upgrade_name" sh -c 'printf "host all all 0.0.0.0/0 trust\n" >> "$PGDATA/pg_hba.conf"'
if docker exec -u postgres "$upgrade_name" sh /opt/x402/upgrade-existing.sh >/dev/null 2>&1; then
  echo 'upgrade accepted TCP trust' >&2; exit 1
fi
docker exec -u postgres "$upgrade_name" sh -c 'cp "$PGDATA/pg_hba.conf.x402test" "$PGDATA/pg_hba.conf"'
docker exec -u postgres "$upgrade_name" sh -c 'printf "invalid hba row\n" >> "$PGDATA/pg_hba.conf"'
if docker exec -u postgres "$upgrade_name" sh /opt/x402/upgrade-existing.sh >/dev/null 2>&1; then
  echo 'upgrade accepted malformed HBA' >&2; exit 1
fi
docker exec -u postgres "$upgrade_name" sh -c 'mv "$PGDATA/pg_hba.conf.x402test" "$PGDATA/pg_hba.conf"'
docker exec -u postgres "$upgrade_name" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres \
  -c 'CREATE ROLE privileged_test; GRANT privileged_test TO facilitator' >/dev/null
if docker exec -u postgres "$upgrade_name" sh /opt/x402/upgrade-existing.sh >/dev/null 2>&1; then
  echo 'upgrade accepted a role with inherited membership' >&2; exit 1
fi
docker exec -u postgres "$upgrade_name" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres \
  -c 'REVOKE privileged_test FROM facilitator; DROP ROLE privileged_test' >/dev/null
echo 'PostgreSQL fresh bootstrap and V1/V2 ownership upgrade passed'
