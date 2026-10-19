#!/usr/bin/env python3
"""OntoForge quality-gate scope, evidence and final-result contract (stdlib only).

Only selects supported S0 suites. Selection is conservative for unknown paths,
both sides of a rename, missing event identities and manual dispatch.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LANES = ("docs", "native", "backend", "frontend", "poc", "e2e")
BACKEND_MODULES = ("ontoforge-common/", "ontoforge-configuration/", "ontoforge-core/",
                   "ontoforge-dao/", "ontoforge-platform/", "ontoforge-business/", "ontoforge-boot/")
RUNTIME_SCHEMA = "doc/design/schema/ontology-package-v0.2.schema.json"


def full_scope() -> dict[str, bool]:
    return {lane: True for lane in LANES}


def select_paths(paths: list[str]) -> dict[str, bool]:
    selected = {lane: False for lane in LANES}
    if not paths:
        return selected
    for path in paths:
        p = path.replace("\\", "/")
        if p.startswith("./"):
            p = p[2:]
        if not p or p.startswith("../") or p.startswith("/"):
            return full_scope()
        if p.startswith((".github/workflows/", "scripts/ci/")):
            return full_scope()
        if p in {"pom.xml", "compose.yaml"} or p.startswith(BACKEND_MODULES + (".mvn/",)):
            selected["backend"] = True
            selected["e2e"] = True
        elif p.startswith("scripts/verify-") and p.endswith(".ps1"):
            selected["e2e"] = True
            if p == "scripts/verify-s0.ps1":
                selected["backend"] = True
        elif p.startswith("ontoforge-ui/"):
            if p.endswith(".md"):
                selected["docs"] = True
            else:
                selected["frontend"] = True
                selected["e2e"] = True
        elif p.startswith("experiments/pg-runtime/"):
            if p.endswith(".md"):
                selected["docs"] = True
            else:
                selected["poc"] = True
        elif p.startswith("doc/"):
            selected["docs"] = True
            if p.startswith("doc/design/"):
                selected["native"] = True
            if p == RUNTIME_SCHEMA:
                selected["backend"] = True
                selected["e2e"] = True
            elif p == "doc/design/fixtures/ofip-0003-jcs-fixtures.json":
                selected["backend"] = True
                selected["e2e"] = True
            elif p == "doc/design/tests/test_native_v02.py":
                selected["e2e"] = True
        elif p in {"README.md", "AGENTS.md", ".github/CONTRIBUTING.md",
                   ".github/OFIP_TEMPLATE.md", ".github/PULL_REQUEST_TEMPLATE.md",
                   "LICENSE", ".gitignore"}:
            selected["docs"] = True
        else:
            # Do not silently skip an unclassified new module or build script.
            return full_scope()
    return selected


def paths_from_name_status(raw: bytes) -> list[str]:
    """Parse git diff --name-status -z, including BOTH paths of R/C changes."""
    fields = raw.split(b"\0")
    if fields[-1] != b"":
        raise ValueError("Truncated NUL-terminated git diff")
    fields.pop()
    paths: list[str] = []
    index = 0
    while index < len(fields):
        status = fields[index].decode("ascii", errors="strict")
        index += 1
        if not status or status[0] not in "AMDRCT":
            raise ValueError(f"Unsupported git status: {status!r}")
        count = 2 if status[0] in "RC" else 1
        if len(fields) - index < count:
            raise ValueError("Incomplete git rename/copy entry")
        for field in fields[index:index + count]:
            path = field.decode("utf-8", errors="strict")
            if not path:
                raise ValueError("Empty git path")
            paths.append(path)
        index += count
    return paths


def discover_scope() -> tuple[dict[str, bool], str, list[str]]:
    event = os.environ.get("GITHUB_EVENT_NAME", "")
    if event == "pull_request":
        base = os.environ.get("CI_BASE_SHA", "")
        head = os.environ.get("CI_HEAD_SHA", "")
        if not base or not head:
            return full_scope(), "full: missing PR base/head", []
        range_spec = base + "..." + head
    elif event == "push":
        before = os.environ.get("CI_BEFORE_SHA", "")
        after = os.environ.get("CI_HEAD_SHA", "")
        if not before or set(before) == {"0"} or not after:
            return full_scope(), "full: missing push ancestor", []
        range_spec = before + ".." + after
    else:
        return full_scope(), "full: workflow_dispatch/unknown event", []

    try:
        raw = subprocess.check_output(
            ["git", "diff", "--name-status", "-z", "--find-renames=50%", range_spec],
            cwd=ROOT, timeout=30,
        )
        paths = paths_from_name_status(raw)
        # Unexpectedly empty PR diff: do NOT claim everything was tested.
        if not paths:
            return full_scope(), "full: empty or unexpected diff", []
        return select_paths(paths), f"changed paths: {len(paths)}", paths
    except (OSError, ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        return full_scope(), f"full: unable to classify diff ({type(exc).__name__})", []


def require_foundation_reports(root: Path = ROOT) -> None:
    """Require actual non-skipped, nonempty S0 PostgreSQL/Flyway JUnit evidence."""
    files = list(root.glob("ontoforge-*/target/surefire-reports/TEST-*.xml"))
    if not files:
        raise ValueError("FAIL: no fresh Surefire XML reports (not PASS)")
    found: set[str] = set()
    executed_classes: set[str] = set()
    for file in files:
        doc = ET.parse(file).getroot()
        suites = [doc] if doc.tag == "testsuite" else doc.findall("testsuite")
        if not suites:
            raise ValueError(f"FAIL: no test suites in {file}")
        for suite in suites:
            for kind in ("errors", "failures", "skipped"):
                if int(suite.attrib.get(kind, "0")):
                    raise ValueError(f"FAIL: {kind} in {file}")
            for test in suite.findall("testcase"):
                if any(test.find(kind) is not None for kind in ("failure", "error", "skipped")):
                    raise ValueError(f"FAIL: testcase failed or skipped in {file}")
                classname = test.attrib.get("classname", "")
                if classname:
                    executed_classes.add(classname)
                if classname.endswith(".FoundationTest"):
                    found.add(test.attrib.get("name", ""))
    required = {"realPostgresMigrationAndApiStatus", "migrationVersionOneExists"}
    if not required <= found:
        raise ValueError(f"FAIL: missing or skipped S0 tests: {sorted(required - found)}")
    # A moved module-local suite must remain observable, not disappear from the
    # evidence gate just because its XML no longer lives under boot/target.
    for folder in root.glob("ontoforge-*/src/test/java"):
        for source in folder.rglob("*Test.java"):
            text = source.read_text(encoding="utf-8")
            if not re.search(r"@(?:org\.junit\.[\w.]+\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b", text):
                continue
            package = re.search(r"(?m)^package\s+([\w.]+)\s*;", text)
            if package is None:
                continue
            expected = package.group(1) + "." + source.stem
            if not any(name == expected or name.startswith(expected + "$") for name in executed_classes):
                raise ValueError(f"FAIL: missing executed module suite: {expected}")
    # Enforce NEW product evidence whenever its implementation is in this checkout.
    # Previously the gate only knew FoundationTest and could misreport a green
    # backend lane without executing any ObjectType PG/HTTP tests.
    draft_source = root / "ontoforge-business/src/main/java/io/ontoforge/business/model/NativeDraftService.java"
    if draft_source.exists():
        required_draft = {
            "nativeDraftRealPostgresCreateWipSaveReloadValidateAndInvalidate",
            "nativeDraftConcurrentSaveDuplicatePackageAndReadFailures",
            "nativeDraftRejectsUnsafeEditorAndInvalidNativeProperties",
            "saveResponseRetainsOwnRevisionWhenAnotherWriterCommitsImmediatelyAfter",
        }
        seen_draft = set()
        for file in files:
            doc = ET.parse(file).getroot()
            suites = [doc] if doc.tag == "testsuite" else doc.findall("testsuite")
            for suite in suites:
                for test in suite.findall("testcase"):
                    if test.attrib.get("classname", "").endswith(".NativeDraftTest"):
                        seen_draft.add(test.attrib.get("name", ""))
        if not required_draft <= seen_draft:
            raise ValueError(f"FAIL: missing real PostgreSQL ObjectType tests: {sorted(required_draft - seen_draft)}")


    snapshot_source = root / "ontoforge-business/src/main/java/io/ontoforge/business/model/NativeSnapshotService.java"
    if snapshot_source.exists():
        must_run = {
            "snapshotRealPostgresFreezeIdempotentReloadAndIntegrity",
            "snapshotRevisionCasUnsupportedAndValidationFailures",
            "snapshotDiffTypeScopedRiskAndRejectedCrossPackage",
            "snapshotDatabaseImmutableAndBadRequests",
            "snapshotCanonicalJcsReferenceDeterminism",
            "snapshotConcurrentWorkersSameKeyExactlyOneSnapshot",
            "snapshotSharedRfc8785GoldenFixtures",
        }
        seen = set()
        for file in files:
            doc = ET.parse(file).getroot()
            suites = [doc] if doc.tag == "testsuite" else doc.findall("testsuite")
            for suite in suites:
                for case in suite.findall("testcase"):
                    if case.attrib.get("classname", "").endswith(".NativeSnapshotTest"):
                        seen.add(case.attrib.get("name", ""))
        if not must_run <= seen:
            raise ValueError(f"FAIL: missing real PostgreSQL Snapshot tests: {sorted(must_run - seen)}")


    runtime_type_source = root / "ontoforge-business/src/main/java/io/ontoforge/business/registry/RuntimeTypeResolver.java"
    if runtime_type_source.exists():
        required_runtime = {
            "validatedDraftCatalogIsDeterministicAndReturnsHonestPropertyContracts",
            "staleUnvalidatedAndMalformedRefsFailClosed",
            "localSnapshotCatalogUsesVerifiedImmutableSnapshotAndDigest",
            "packageAuthorizationIsRecheckedForEveryTypeRead",
        }
        seen_runtime = set()
        for file in files:
            doc = ET.parse(file).getroot()
            suites = [doc] if doc.tag == "testsuite" else doc.findall("testsuite")
            for suite in suites:
                for case in suite.findall("testcase"):
                    if case.attrib.get("classname", "").endswith(".RuntimeTypeTest"):
                        seen_runtime.add(case.attrib.get("name", ""))
        if not required_runtime <= seen_runtime:
            raise ValueError(f"FAIL: missing real PostgreSQL Runtime Type tests: {sorted(required_runtime - seen_runtime)}")


    security_source = root / "ontoforge-boot/src/main/java/io/ontoforge/boot/security/SecurityConfig.java"
    if security_source.exists():
        required_security = {"wrongIssuerAudienceAndExpiredTokensAreRejected",
                             "revokedEditorMembershipImmediatelyRemovesPackageAccess",
                             "clientSuppliedIdentityHeadersDoNotElevateViewer"}
        seen_security = set()
        for file in files:
            doc = ET.parse(file).getroot()
            suites = [doc] if doc.tag == "testsuite" else doc.findall("testsuite")
            for suite in suites:
                for case in suite.findall("testcase"):
                    if case.attrib.get("classname", "").endswith(".B0IdentityAuthorizationTest"):
                        seen_security.add(case.attrib.get("name", ""))
        if not required_security <= seen_security:
            raise ValueError(f"FAIL: missing real JWT/PostgreSQL authorization tests: {sorted(required_security - seen_security)}")


def evaluate_results(selected: dict[str, bool], results: dict[str, str], scope_result: str) -> list[tuple[str, str]]:
    if scope_result != "success":
        raise ValueError(f"FAIL: selection job {scope_result}")
    if set(selected) != set(LANES) or any(type(v) is not bool for v in selected.values()):
        raise ValueError("FAIL: invalid or incomplete selected-scope JSON")
    rows = []
    for lane in LANES:
        result = results.get(lane, "")
        if selected[lane]:
            if result != "success":
                raise ValueError(f"FAIL: selected {lane} job is {result or 'missing'}")
            rows.append((lane, "PASS (selected)"))
        else:
            if result != "skipped":
                raise ValueError(f"FAIL: unselected {lane} unexpectedly returned {result or 'missing'}")
            rows.append((lane, "NOT SELECTED"))
    return rows


def main() -> None:
    if len(sys.argv) != 2 or sys.argv[1] not in {"select", "check", "verify-reports"}:
        raise SystemExit("Usage: quality_ci.py {select|check|verify-reports}")
    action = sys.argv[1]
    if action == "verify-reports":
        require_foundation_reports()
        print("PASS: actual FoundationTest PostgreSQL/Flyway XML evidence verified")
        return
    if action == "select":
        scope, reason, paths = discover_scope()
        output = os.environ.get("GITHUB_OUTPUT")
        content = "".join(f"{lane}={str(enabled).lower()}\n" for lane, enabled in scope.items())
        content += "selected=" + json.dumps(scope, separators=(",", ":")) + "\n"
        if output:
            with open(output, "a", encoding="utf-8") as f:
                f.write(content)
        else:
            print(content, end="")
        print("Selected suites:", json.dumps(scope), reason)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as f:
                f.write(f"### Quality suite selection\n\n{reason}\n\n")
                f.write("| Suite | Selected? |\n| --- | --- |\n")
                for lane, flag in scope.items():
                    f.write(f"| {lane} | {str(flag).upper()} |\n")
                f.write(f"\nPR base: {os.getenv('CI_BASE_SHA', 'N/A')}; source head: {os.getenv('CI_HEAD_SHA', 'N/A')}\n")
        return
    selected = json.loads(os.environ.get("CI_SELECTED_JSON", "null"))
    if not isinstance(selected, dict):
        raise ValueError("FAIL: selected scope missing")
    results = {lane: os.environ.get("CI_" + lane.upper() + "_RESULT", "") for lane in LANES}
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    try:
        rows = evaluate_results(selected, results, os.environ.get("CI_SCOPE_RESULT", ""))
        state = "PASS"
    except (ValueError, TypeError) as exc:
        state = "FAIL"
        rows = [(lane, results[lane] or "MISSING") for lane in LANES]
        print(str(exc), file=sys.stderr)
    report = f"### Required Quality Gate: {state}\n\n"
    report += "| Suite | Selected | Result |\n| --- | --- | --- |\n"
    for lane, result in rows:
        report += f"| {lane} | {selected.get(lane, 'MISSING')} | {result} |\n"
    report += "\nNOT SELECTED does not mean a test passed. Selected E2E uses real PG/API/React/Chromium; Windows manual and release acceptance remain NOT RUN.\n"
    report += f"\nCheckout SHA: {os.getenv('GITHUB_SHA', 'N/A')}; PR head SHA: {os.getenv('CI_HEAD_SHA', 'N/A')}\n"
    print(report)
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(report)
    if state != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
