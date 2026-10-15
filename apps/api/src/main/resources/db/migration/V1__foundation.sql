-- S0 v0.1 has no user, role, membership or business models.
-- A persisted marker is enough to prove real PostgreSQL migration and restart behavior.
CREATE TABLE platform_installation (
  id SMALLINT PRIMARY KEY CHECK (id = 1),
  installation_id UUID NOT NULL,
  installed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO platform_installation (id, installation_id)
VALUES (1, '00000000-0000-0000-0000-000000000001');
