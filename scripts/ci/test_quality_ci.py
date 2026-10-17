"""Tests for selected CI scope, rename safety and honest quality/evidence status."""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import sys

MODULE = Path(__file__).with_name("quality_ci.py")
spec = importlib.util.spec_from_file_location("quality_ci", MODULE)
quality_ci = importlib.util.module_from_spec(spec)
sys.modules["quality_ci"] = quality_ci
spec.loader.exec_module(quality_ci)


class ScopeTests(unittest.TestCase):
    def test_docs_only_no_runtime_pass_claim(self):
        s = quality_ci.select_paths(["doc/proposals/OFIP-0001.md", "AGENTS.md"])
        self.assertEqual(s, dict(docs=True, native=False, backend=False, frontend=False, poc=False, e2e=False))

    def test_native_contract_always_includes_docs(self):
        s = quality_ci.select_paths(["doc/design/schema/ontology-ast-v0.2.schema.json"])
        self.assertTrue(s["docs"] and s["native"])
        self.assertFalse(s["backend"] or s["frontend"] or s["poc"])

    def test_runtime_schema_and_shared_vectors_select_actual_consumers(self):
        for path in (quality_ci.RUNTIME_SCHEMA, "doc/design/fixtures/ofip-0003-jcs-fixtures.json"):
            with self.subTest(path=path):
                s = quality_ci.select_paths([path])
                self.assertTrue(all(s[lane] for lane in ("docs", "native", "backend", "e2e")))

    def test_actual_product_changes_and_acceptance_scripts_require_e2e(self):
        for path in ("ontoforge-ui/src/main.tsx", "pom.xml", "scripts/verify-linktype.ps1"):
            with self.subTest(path=path):
                self.assertTrue(quality_ci.select_paths([path])["e2e"])

    def test_selected_e2e_failure_cancel_skip_or_missing_blocks_gate(self):
        s = quality_ci.select_paths(["ontoforge-ui/src/main.tsx"])
        results = {lane: "success" if enabled else "skipped" for lane, enabled in s.items()}
        self.assertIn(("e2e", "PASS (selected)"), quality_ci.evaluate_results(s, results, "success"))
        for result in ("failure", "cancelled", "skipped", ""):
            with self.subTest(result=result), self.assertRaises(ValueError):
                quality_ci.evaluate_results(s, {**results, "e2e": result}, "success")

    def test_backend_flyway_and_frontend(self):
        s = quality_ci.select_paths(["ontoforge-boot/src/main/resources/db/migration/V2__model.sql",
                                     "ontoforge-ui/src/main.tsx"])
        self.assertTrue(s["backend"] and s["frontend"])
        self.assertFalse(s["poc"])

    def test_experiment_code_and_readme_are_not_confused(self):
        self.assertEqual(quality_ci.select_paths(["experiments/pg-runtime/README.md"])["poc"], False)
        self.assertTrue(quality_ci.select_paths(["experiments/pg-runtime/run_final_gates.py"])["poc"])

    def test_ci_and_unknown_path_fail_closed(self):
        for name in (".github/workflows/quality-gate.yml", "scripts/ci/quality_ci.py",
                     "new-module/src/main/java/Test.java", ".editorconfig"):
            with self.subTest(name=name):
                self.assertEqual(quality_ci.select_paths([name]), quality_ci.full_scope())

    def test_rename_tracks_both_old_and_new_paths(self):
        data = b"R100\0ontoforge-boot/src/Old.java\0ontoforge-ui/src/New.tsx\0"
        paths = quality_ci.paths_from_name_status(data)
        self.assertEqual(paths, ["ontoforge-boot/src/Old.java", "ontoforge-ui/src/New.tsx"])
        self.assertTrue(quality_ci.select_paths(paths)["backend"])
        self.assertTrue(quality_ci.select_paths(paths)["frontend"])

    def test_copy_modified_and_deleted(self):
        raw = b"M\0README.md\0C100\0foo\0bar\0D\0pom.xml\0"
        self.assertEqual(quality_ci.paths_from_name_status(raw), ["README.md", "foo", "bar", "pom.xml"])

    def test_malformed_diff_cannot_silently_skip(self):
        for raw in (b"R100\0only-one-name\0", b"M\0unclosed", b"?", b"X\0file\0"):
            with self.subTest(raw=raw):
                with self.assertRaises((ValueError, UnicodeDecodeError)):
                    quality_ci.paths_from_name_status(raw)

    def test_missing_identity_dispatch_and_unexpected_empty_diff_are_full(self):
        with patch.dict("os.environ", {"GITHUB_EVENT_NAME": "pull_request", "CI_BASE_SHA": "", "CI_HEAD_SHA": ""}):
            self.assertEqual(quality_ci.discover_scope()[0], quality_ci.full_scope())
        with patch.dict("os.environ", {"GITHUB_EVENT_NAME": "workflow_dispatch"}):
            self.assertEqual(quality_ci.discover_scope()[0], quality_ci.full_scope())
        with patch.dict("os.environ", {"GITHUB_EVENT_NAME": "pull_request", "CI_BASE_SHA": "a" * 40, "CI_HEAD_SHA": "b" * 40}):
            with patch.object(quality_ci.subprocess, "check_output", return_value=b""):
                self.assertEqual(quality_ci.discover_scope()[0], quality_ci.full_scope())

    def test_selected_jobs_must_actually_succeed_skipped_cannot_pass(self):
        s = quality_ci.select_paths(["README.md"])
        r = dict(docs="success", native="skipped", backend="skipped", frontend="skipped", poc="skipped", e2e="skipped")
        self.assertIn(("docs", "PASS (selected)"), quality_ci.evaluate_results(s, r, "success"))
        self.assertIn(("backend", "NOT SELECTED"), quality_ci.evaluate_results(s, r, "success"))
        with self.assertRaises(ValueError):
            quality_ci.evaluate_results(s, {**r, "docs": "skipped"}, "success")
        with self.assertRaises(ValueError):
            quality_ci.evaluate_results(s, {**r, "backend": "success"}, "success")
        with self.assertRaises(ValueError):
            quality_ci.evaluate_results(s, r, "failure")
        with self.assertRaises(ValueError):
            quality_ci.evaluate_results(s, {**r, "docs": ""}, "success")

    def test_fails_if_backend_reports_are_missing(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "no fresh Surefire"):
                quality_ci.require_foundation_reports(Path(folder))

    def test_reject_skipped_and_partial_reports(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "ontoforge-boot/target/surefire-reports"
            output.mkdir(parents=True)
            file = output / "TEST-io.ontoforge.boot.FoundationTest.xml"
            file.write_text(
                '<testsuite tests="2" failures="0" errors="0" skipped="1">'
                '<testcase classname="io.ontoforge.boot.FoundationTest" name="realPostgresMigrationAndApiStatus"/>'
                '<testcase classname="io.ontoforge.boot.FoundationTest" name="migrationVersionOneExists"><skipped/></testcase>'
                '</testsuite>', encoding="utf-8"
            )
            with self.assertRaises(ValueError):
                quality_ci.require_foundation_reports(Path(folder))
            file.write_text(
                '<testsuite tests="2" failures="0" errors="0" skipped="0">'
                '<testcase classname="io.ontoforge.boot.FoundationTest" name="realPostgresMigrationAndApiStatus"/>'
                '<testcase classname="io.ontoforge.boot.FoundationTest" name="migrationVersionOneExists"/>'
                '</testsuite>', encoding="utf-8"
            )
            quality_ci.require_foundation_reports(Path(folder))

            # Foundation evidence alone cannot qualify a checkout with authentication.
            security = Path(folder) / "ontoforge-boot/src/main/java/io/ontoforge/boot/security/SecurityConfig.java"
            security.parent.mkdir(parents=True)
            security.write_text("// test presence", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "missing real JWT/PostgreSQL authorization tests"):
                quality_ci.require_foundation_reports(Path(folder))


if __name__ == "__main__":
    unittest.main()
