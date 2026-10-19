-- OFIP-0002 S0-MVP-0.2: one editable draft per package in the local installation.
-- Native package releases and ObjectInstances are intentionally NOT stored here.
CREATE TABLE native_package_draft (
    draft_id UUID PRIMARY KEY,
    package_id TEXT NOT NULL UNIQUE,
    editor_schema_version TEXT NOT NULL CHECK (editor_schema_version = '0.1.0'),
    editing_document JSONB NOT NULL CHECK (jsonb_typeof(editing_document) = 'object'),
    revision BIGINT NOT NULL CHECK (revision >= 1),
    validated_revision BIGINT NULL,
    validation_report JSONB NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT native_draft_validated_rev_ck CHECK (
        validated_revision IS NULL OR
        (validated_revision >= 1 AND validated_revision <= revision)
    )
);
CREATE INDEX native_draft_updated_idx ON native_package_draft (updated_at DESC, draft_id DESC);
