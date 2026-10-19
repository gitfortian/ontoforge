-- Existing workspace identities, membership roles and unique package ownership remain intact.
-- A workspace revision covers its name, complete membership set and package registry.
ALTER TABLE b0_workspace ADD COLUMN version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0);
