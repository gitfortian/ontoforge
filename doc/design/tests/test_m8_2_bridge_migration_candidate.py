"""Review-only invariants for the M8.2 BridgeTable forward SQL CANDIDATE.

These tests intentionally do not claim that the migration has run on real PG17.
Real upgrade/immutability/CAS/rollback requires a post-vote isolated database
integration suite. Production migrations MUST NOT be added until OFIP approved.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
SQL = ROOT / "doc/design/candidates/m8-2/V11__bridge_link_binding_candidate.sql"
V10 = ROOT / "ontoforge-dao/src/main/resources/db/migration/V10__local_link_binding_revisions.sql"


class BridgeMigrationCandidateReview(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ddl = SQL.read_text(encoding="utf-8")
        cls.v10 = V10.read_text(encoding="utf-8")
        cls.active = re.sub(r"--[^\n]*", "", cls.ddl)
        cls.active = re.sub(r"/\*.*?\*/", "", cls.active, flags=re.S)

    def test_nonproduction_candidate_does_not_live_in_flyway_directory(self):
        self.assertTrue(SQL.exists())
        self.assertFalse(str(SQL).startswith(str(ROOT / "ontoforge-dao/src/main/resources/db/migration")))
        self.assertIn("NOT A FLYWAY MIGRATION", self.ddl)

    def test_v10_original_fk_columns_and_append_only_triggers_existed(self):
        for field in ("fk_owner TEXT NOT NULL", "fk_source_column TEXT NOT NULL", "referenced_identity_column TEXT NOT NULL"):
            self.assertIn(field, self.v10)
        self.assertIn("trg_runtime_link_binding_revision_immutable", self.v10)
        self.assertIn("trg_runtime_link_binding_operation_immutable", self.v10)
        self.assertIn("content_digest CHAR(64) NOT NULL", self.v10)

    def test_no_create_head_no_rewrite_old_rows_no_trigger_mutation(self):
        self.assertNotRegex(self.active, r"(?i)\b(UPDATE|DELETE|TRUNCATE)\b")
        self.assertNotRegex(self.active, r"(?i)\b(DROP\s+TRIGGER|DROP\s+TABLE|CREATE\s+TABLE)\b")
        self.assertNotIn("content_digest =", self.active)
        self.assertNotIn("runtime_link_binding_operation", self.active)
        self.assertRegex(self.active, r"(?i)ALTER TABLE runtime_link_binding_revision")

    def test_default_discriminator_preserves_fk_only_old_rows(self):
        self.assertRegex(self.active, r"mapping_mode\s+TEXT\s+NOT NULL\s+DEFAULT\s+'FOREIGN_KEY'")
        for field in ("fk_owner", "fk_source_column", "referenced_identity_column"):
            self.assertRegex(self.active, rf"ALTER COLUMN\s+{field}\s+DROP NOT NULL")
        self.assertRegex(self.active, r"mapping_mode\s*=\s*'FOREIGN_KEY'")
        self.assertRegex(self.active, r"mapping_mode\s*=\s*'BRIDGE_TABLE'")

    def test_mutually_exclusive_source_and_bridge_fields_and_fingerprint(self):
        for col in ("bridge_source_id", "bridge_schema", "bridge_table",
                    "bridge_source_fk_column", "bridge_target_fk_column",
                    "bridge_schema_fingerprint"):
            self.assertRegex(self.active, rf"{col}\s+IS\s+NULL")
            self.assertRegex(self.active, rf"{col}\s+IS\s+NOT\s+NULL")
        for col in ("fk_owner", "fk_source_column", "referenced_identity_column"):
            self.assertRegex(self.active, rf"{col}\s+IS\s+NULL")
            self.assertRegex(self.active, rf"{col}\s+IS\s+NOT\s+NULL")
        self.assertIn("bridge_schema_fingerprint ~ '^[0-9a-f]{64}$'", self.active)
        self.assertIn("bridge_source_fk_column <> bridge_target_fk_column", self.active)
        self.assertIn("bridge_schema = 'public'", self.active)

    def test_no_client_supplied_sql_or_policy_evasion_claim(self):
        self.assertIn("not enforce sourceId allowlist", self.ddl)
        self.assertIn("mandatory runtime validations", self.ddl)
        self.assertIn("REPEATABLE_READ", self.ddl)


if __name__ == "__main__":
    unittest.main(verbosity=2)
