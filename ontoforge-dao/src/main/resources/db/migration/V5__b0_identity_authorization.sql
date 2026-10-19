-- OFIP-0005 B0: trusted principals, workspace membership and package assignment.
-- Existing V1-V3 tables are intentionally untouched.
CREATE TABLE b0_workspace (
    workspace_id UUID PRIMARY KEY,
    workspace_key TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE b0_principal (
    principal_id UUID PRIMARY KEY,
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    display_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT b0_principal_identity_uq UNIQUE (issuer, subject),
    CONSTRAINT b0_principal_issuer_ck CHECK (length(trim(issuer)) > 0),
    CONSTRAINT b0_principal_subject_ck CHECK (length(trim(subject)) > 0)
);

CREATE TABLE b0_workspace_membership (
    workspace_id UUID NOT NULL REFERENCES b0_workspace(workspace_id) ON DELETE CASCADE,
    principal_id UUID NOT NULL REFERENCES b0_principal(principal_id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN (
        'VIEWER','EDITOR','REVIEWER','PUBLISHER','ACTIVATOR','OWNER'
    )),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (workspace_id, principal_id, role)
);

CREATE TABLE b0_package_assignment (
    workspace_id UUID NOT NULL REFERENCES b0_workspace(workspace_id) ON DELETE CASCADE,
    package_id TEXT NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (workspace_id, package_id),
    CONSTRAINT b0_package_assignment_package_uq UNIQUE (package_id)
);

CREATE INDEX b0_membership_principal_idx
    ON b0_workspace_membership (principal_id, workspace_id);

CREATE INDEX b0_package_assignment_package_idx
    ON b0_package_assignment (package_id, workspace_id);

-- No automatic package claim is performed here. Existing packages must be
-- explicitly assigned to a workspace by an authorized migration path.
