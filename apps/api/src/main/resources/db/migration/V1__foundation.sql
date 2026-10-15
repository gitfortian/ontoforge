-- PostgreSQL-only S0 migration. Do not reuse Yak Ops' MySQL schema.
CREATE TABLE app_user (
 id UUID PRIMARY KEY,
 username VARCHAR(80) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE app_workspace (
 id UUID PRIMARY KEY,
 name VARCHAR(80) NOT NULL,
 created_by UUID NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE app_workspace_member (
 workspace_id UUID NOT NULL REFERENCES app_workspace(id) ON DELETE CASCADE,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 role VARCHAR(16) NOT NULL CHECK (role IN ('OWNER','MEMBER')),
 joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(workspace_id,user_id)
);
CREATE INDEX idx_workspace_member_user ON app_workspace_member(user_id, workspace_id);
