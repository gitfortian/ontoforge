-- OFIP-0017: forward-only local credentials; no existing principal or package is claimed.
CREATE TABLE platform_local_account (
    account_id UUID PRIMARY KEY,
    principal_id UUID NOT NULL UNIQUE REFERENCES b0_principal(principal_id),
    username VARCHAR(64) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9._-]{3,64}$'),
    password_hash TEXT NOT NULL CHECK (length(password_hash) > 0),
    enabled BOOLEAN NOT NULL DEFAULT true,
    system_admin BOOLEAN NOT NULL DEFAULT false,
    credential_version BIGINT NOT NULL DEFAULT 1 CHECK (credential_version > 0),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
