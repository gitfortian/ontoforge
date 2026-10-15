"""Regression gates for the single active architecture baseline and doc links.

Run from repository root:
python -m unittest discover -s doc/tests -p 'test_architecture_docs.py' -v
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / "doc"
CURRENT = DOC / "本体产品总体功能与技术架构设计_v1.3.md"

RETIRED_DOCS = [
    "doc/本体产品功能架构与技术架构设计_v1.0.md",
    "doc/本体产品功能架构与技术架构设计_v1.1.md",
    "doc/本体产品功能架构与技术架构设计_v1.2.md",
    "doc/design/核心本体元模型与DSL-AST契约_v0.1.md",
    "doc/design/tests/DSL-AST-契约验收案例_v0.1.md",
    "doc/research/2026-10-15-POC-Query-Edit-实测与架构决策_v0.1.md",
    "doc/research/2026-10-15-POC-Query-Edit-第二轮验证报告.md",
    "doc/research/2026-10-15-POC-PostgreSQL17-ObjectSet-Outbox-真实运行验收.md",
    "doc/research/2026-10-15-ObjectSet-Action-架构选型收口与冻结提案.md",
]

LEGACY_FIXTURES = [
    "doc/design/schema/ontology-package-v0.1.schema.json",
    "doc/design/schema/ontology-ast-v0.1.schema.json",
    "doc/design/examples/manufacturing-quality-v0.1.json",
    "doc/design/examples/manufacturing-quality-ast-v0.1.json",
    "doc/design/examples/manufacturing-quality-invalid-data-property-v0.1.json",
]

ACTIVE_CONTRACTS = [
    "doc/design/核心本体元模型与Native-DSL-AST契约_v0.2.md",
    "doc/design/schema/ontology-package-v0.2.schema.json",
    "doc/design/schema/ontology-ast-v0.2.schema.json",
    "doc/design/schema/objectset-query-v0.2.schema.json",
    "doc/design/schema/objectset-result-v0.2.schema.json",
    "doc/design/schema/semantic-execution-record-v0.2.schema.json",
    "doc/design/V0.1到NativeV0.2迁移矩阵.md",
    "doc/design/tests/test_native_v02.py",
]

LINKS = re.compile(r"\]\(([^)\s]+)(?:\s+['\"][^'\"]*['\"])?\)")
GONE_NAMES = tuple(Path(x).name for x in RETIRED_DOCS)
DOC_PATHS = list(DOC.rglob("*.md")) + [ROOT / "README.md", ROOT / "experiments/pg-runtime/README.md"]


class ArchitectureDocsTests(unittest.TestCase):
    def test_one_current_architecture_document(self):
        self.assertTrue(CURRENT.is_file())
        self.assertEqual(
            [p.name for p in DOC.glob("*架构设计_v*.md") if re.search(r"v1\\.[012]\\.md$", p.name)], [],
            "Retired V1.0-V1.2 architecture drafts must not compete with V1.3",
        )
        v13 = CURRENT.read_text(encoding="utf-8")
        self.assertIn("唯一", v13)
        self.assertIn("待签收", v13)

    def test_all_legacy_markdown_has_left_active_tree(self):
        for file in RETIRED_DOCS:
            with self.subTest(path=file):
                self.assertFalse((ROOT / file).exists())

    def test_legacy_machine_fixtures_preserved_for_migration(self):
        for file in LEGACY_FIXTURES:
            with self.subTest(path=file):
                self.assertTrue((ROOT / file).is_file())

    def test_native_contracts_remain_current(self):
        for file in ACTIVE_CONTRACTS:
            with self.subTest(path=file):
                self.assertTrue((ROOT / file).is_file())

    def test_model_domain_feature_coverage(self):
        v13 = CURRENT.read_text(encoding="utf-8")
        ids = set(re.findall(r"\bF(?:0[1-9]|1\d|2\d)\b", v13))
        self.assertEqual(ids, {f"F{i:02d}" for i in range(1, 30)})

    def test_all_relative_markdown_links_resolve(self):
        missing = []
        for path in DOC_PATHS:
            text = path.read_text(encoding="utf-8")
            for m in LINKS.finditer(text):
                target = m.group(1).split("#", 1)[0]
                if not target or target.startswith(("http:", "https:", "mailto:", "data:", "/")):
                    continue
                dest = (path.parent / unquote(target)).resolve()
                if not dest.exists():
                    missing.append((str(path.relative_to(ROOT)), target))
        self.assertEqual(missing, [])

    def test_no_stale_links_to_deleted_docs(self):
        findings = []
        for path in DOC_PATHS:
            text = path.read_text(encoding="utf-8")
            for filename in GONE_NAMES:
                if filename in text:
                    findings.append((str(path.relative_to(ROOT)), filename))
        self.assertEqual(findings, [])

    def test_readmes_route_to_single_baseline(self):
        for p in (ROOT / "README.md", DOC / "README.md"):
            with self.subTest(readme=str(p.relative_to(ROOT))):
                s = p.read_text(encoding="utf-8")
                self.assertIn("本体产品总体功能与技术架构设计_v1.3.md", s)


if __name__ == "__main__":
    unittest.main()
