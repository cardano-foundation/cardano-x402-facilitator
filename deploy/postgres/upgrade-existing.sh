#!/bin/sh
# Run only after stopping writers and backing up the existing volume.
set -eu
test -n "${FACILITATOR_DB_PASSWORD:-}" && test -n "${YACI_DB_PASSWORD:-}"
test -n "${POSTGRES_ADMIN_PASSWORD:-}"
test "${FACILITATOR_DB_PASSWORD}" != "${YACI_DB_PASSWORD}"
test "${FACILITATOR_DB_PASSWORD}" != "${POSTGRES_ADMIN_PASSWORD}"
test "${YACI_DB_PASSWORD}" != "${POSTGRES_ADMIN_PASSWORD}"
psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres <<'SQL'
\getenv fpass FACILITATOR_DB_PASSWORD
\getenv ypass YACI_DB_PASSWORD
\getenv adminpass POSTGRES_ADMIN_PASSWORD
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_hba_file_rules WHERE error IS NOT NULL) THEN
    RAISE EXCEPTION 'pg_hba.conf has parsing errors; correct and reload before upgrade';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_hba_file_rules
    WHERE type LIKE 'host%' AND auth_method = 'trust') THEN
    RAISE EXCEPTION 'pg_hba.conf permits trust over TCP; require SCRAM rules and reload before upgrade';
  END IF;
END $$;
BEGIN;
SET LOCAL password_encryption = 'scram-sha-256';
ALTER ROLE postgres PASSWORD :'adminpass';
SELECT 'CREATE ROLE facilitator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'facilitator') \gexec
SELECT 'CREATE ROLE yaci_store LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'yaci_store') \gexec
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member
    WHERE r.rolname IN ('facilitator', 'yaci_store')) THEN
    RAISE EXCEPTION 'application role already has role membership; inspect and revoke it before upgrade';
  END IF;
END $$;
ALTER ROLE facilitator WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD :'fpass';
ALTER ROLE yaci_store WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD :'ypass';
DO $$
DECLARE s text; target_role text; obj record; kind text;
BEGIN
  FOREACH s IN ARRAY ARRAY['facilitator', 'yaci_store'] LOOP
    target_role := s;
    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = s) THEN
      EXECUTE format('CREATE SCHEMA %I AUTHORIZATION %I', s, target_role);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname=s
      AND pg_get_userbyid(nspowner) NOT IN ('postgres', target_role)) THEN
      RAISE EXCEPTION 'unexpected schema owner in %', s;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname=s AND pg_get_userbyid(c.relowner) NOT IN ('postgres', target_role)) THEN
      RAISE EXCEPTION 'unexpected relation owner in %', s;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE n.nspname=s AND pg_get_userbyid(p.proowner) NOT IN ('postgres', target_role)) THEN
      RAISE EXCEPTION 'unexpected routine owner in %', s;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
      WHERE n.nspname=s AND t.typrelid=0 AND t.typname NOT LIKE '\_%' ESCAPE '\'
        AND pg_get_userbyid(t.typowner) NOT IN ('postgres', target_role)) THEN
      RAISE EXCEPTION 'unexpected type owner in %', s;
    END IF;
    IF EXISTS (
      SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname=s AND c.relkind NOT IN ('r','p','S','v','m','f','i','I','t')
    ) THEN RAISE EXCEPTION 'unsupported relation kind in schema %', s; END IF;
    FOR obj IN SELECT c.relname, c.relkind FROM pg_class c
      JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname=s AND c.relkind IN ('r','p','S','v','m','f')
      ORDER BY CASE c.relkind WHEN 'S' THEN 2 ELSE 1 END
    LOOP
      kind := CASE obj.relkind WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW'
        WHEN 'm' THEN 'MATERIALIZED VIEW' WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END;
      EXECUTE format('ALTER %s %I.%I OWNER TO %I', kind, s, obj.relname, target_role);
    END LOOP;
    FOR obj IN SELECT p.oid, p.prokind FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE n.nspname=s LOOP
      kind := CASE obj.prokind WHEN 'p' THEN 'PROCEDURE' WHEN 'a' THEN 'AGGREGATE' ELSE 'FUNCTION' END;
      EXECUTE format('ALTER %s %s OWNER TO %I', kind, obj.oid::regprocedure, target_role);
    END LOOP;
    FOR obj IN SELECT t.typname, t.typtype FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
      WHERE n.nspname=s AND t.typrelid=0 AND t.typname NOT LIKE '\_%' ESCAPE '\'
    LOOP
      kind := CASE obj.typtype WHEN 'd' THEN 'DOMAIN' ELSE 'TYPE' END;
      EXECUTE format('ALTER %s %I.%I OWNER TO %I', kind, s, obj.typname, target_role);
    END LOOP;
    EXECUTE format('ALTER SCHEMA %I OWNER TO %I', s, target_role);
  END LOOP;
END $$;
REVOKE ALL ON SCHEMA facilitator, yaci_store FROM PUBLIC;
REVOKE ALL ON SCHEMA facilitator FROM yaci_store;
REVOKE ALL ON SCHEMA yaci_store FROM facilitator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE postgres TO facilitator, yaci_store;
COMMIT;
SQL
