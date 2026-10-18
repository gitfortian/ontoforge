\set ON_ERROR_STOP on
\getenv reader_password ONTOFORGE_BINDING_SOURCE_PASSWORD

-- The Flyway V7 migration creates the NOLOGIN group. Reconcile its role flags
-- and table grants before provisioning the dedicated login.
ALTER ROLE ontoforge_binding_reader WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
REVOKE ALL PRIVILEGES ON SCHEMA public FROM ontoforge_binding_reader;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM ontoforge_binding_reader;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM ontoforge_binding_reader;
GRANT USAGE ON SCHEMA public TO ontoforge_binding_reader;
GRANT SELECT ON TABLE public.m5_supplier_source TO ontoforge_binding_reader;

SELECT format(
  'CREATE ROLE ontoforge_binding_login LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT',
  :'reader_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ontoforge_binding_login')
\gexec

SELECT format(
  'ALTER ROLE ontoforge_binding_login WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT',
  :'reader_password')
\gexec

DO $$
DECLARE member_role RECORD;
BEGIN
  FOR member_role IN
    SELECT granted.rolname
      FROM pg_auth_members membership
      JOIN pg_roles granted ON granted.oid = membership.roleid
      JOIN pg_roles member ON member.oid = membership.member
     WHERE member.rolname = 'ontoforge_binding_login'
  LOOP
    EXECUTE format('REVOKE %I FROM ontoforge_binding_login', member_role.rolname);
  END LOOP;
END $$;

SELECT format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM ontoforge_binding_login', current_database())
\gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO ontoforge_binding_login', current_database())
\gexec
REVOKE ALL PRIVILEGES ON SCHEMA public FROM ontoforge_binding_login;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM ontoforge_binding_login;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM ontoforge_binding_login;
GRANT ontoforge_binding_reader TO ontoforge_binding_login;
