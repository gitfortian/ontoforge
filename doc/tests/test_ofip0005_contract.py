"""P0-B TC-2 contract guard: protect irreversible semantic boundaries until approved.

This is a docs-only design test. It does NOT exercise real authentication,
review, publication or activation and must not be reported as implementation.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
OFIP = ROOT / "doc/proposals/OFIP-0005-P0B模型治理审核发布与激活MVP.md"
GLOSSARY = ROOT / "doc/砺元本体产品统一术语词汇表.md"
SOURCE = ROOT / "ontoforge-core/src/main/java/io/ontoforge/core/compiler/DraftValidation.java"


class P0BContractDocumentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = OFIP.read_text(encoding="utf-8")

    def test_design_status_does_not_imply_implementation(self):
        self.assertIn("**Status:** Discussion", self.doc)
        self.assertIn("**Decision:** Pending", self.doc)
        self.assertIn("**Implementation:** Not Started", self.doc)
        self.assertIn("**Release:** Not Released", self.doc)

    def test_all_contract_dimensions_present(self):
        for n in range(1, 14):
            with self.subTest(section=n):
                self.assertRegex(self.doc, rf"(?m)^#### TC{n:02d} ·")
        for concept in ("schemaVersion", "payloadSha256", "ChangeSet",
                        "ReviewDecision", "QualityReport", "SchemaRelease",
                        "ActivationEvent", "Approval Digest", "ReleaseManifest",
                        "lockDigest", "expectedActiveRevision", "candidateDigest",
                        "401", "403", "409", "412", "422", "RESULT_UNKNOWN"):
            with self.subTest(concept=concept):
                self.assertIn(concept, self.doc)

    def test_auth_and_old_draft_endpoints_not_exempted(self):
        for fact in ("NativeDraftController", "NativeSnapshotController",
                     "old", "issuer", "audience", "no Token",
                     "401", "403", "X-Role", "sourceSnapshotId"):
            # Existing endpoints have no auth and require migration, not fake review.
            canonical = {"old": "旧", "no Token": "无 Token"}
            self.assertIn(canonical.get(fact, fact), self.doc)

    def test_confirmed_s0_candidate_version_matches_real_code(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn('candidate.put("version","0.1.0")', source)
        self.assertIn("version=0.1.0", self.doc)
        self.assertIn("ReleaseManifest", self.doc)

    def test_prevent_confusing_snapshots_with_releases_or_real_production(self):
        for phrase in ("不能", "NOT RUN", "旧快照", "不允许", "不能把",
                       "Windows 11", "UNKNOWN", "职责分离",
                       "原 Snapshot"):
            self.assertIn(phrase, self.doc)
        self.assertIn("本地模型快照", GLOSSARY.read_text(encoding="utf-8"))

    def test_accepted_native_v02_and_no_release_contract_confusion(self):
        sections = ("Motivation", "Public Interfaces", "Proposed Changes",
                    "Compatibility, Deprecation, and Migration Plan",
                    "Test Plan", "Rejected Alternatives")
        for s in sections:
            self.assertIn("## " + s, self.doc)


if __name__ == "__main__":
    unittest.main()
