"""Guard canonical OntoForge bilingual product glossary and onboarding links."""
import re
import unittest
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
GLOSSARY = ROOT / "doc" / "砺元本体产品统一术语词汇表.md"
INTRO = [ROOT / "README.md", ROOT / "AGENTS.md", ROOT / "doc" / "README.md",
         ROOT / ".github" / "CONTRIBUTING.md"]

class OntoForgeGlossaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = GLOSSARY.read_text(encoding="utf-8")
        cls.items = []
        cls.sections = []
        for line in cls.doc.splitlines():
            if re.match(r"^## \d\d｜", line):
                cls.sections.append(line)
            if not line.startswith("| **") or line.startswith("| **["):
                continue
            parts = [cell.strip() for cell in line.strip().strip("|").split("|")]
            if len(parts) == 5 and parts[0].startswith("**") and parts[0].endswith("**"):
                cls.items.append(parts)

    def test_has_all_product_domains_and_no_duplicate_preferred_terms(self):
        self.assertEqual(9, len(self.sections))
        self.assertGreaterEqual(len(self.items), 250)
        names = [row[0].strip("*") for row in self.items]
        self.assertEqual(len(names), len(set(names)), "Chinese preferred names must be unique")
        self.assertTrue(all(all(cell for cell in row) for row in self.items))

    def test_critical_boundaries_and_evidence_levels(self):
        for name in (
            "对象类型", "对象实例", "关系类型", "关系事实",
            "草稿", "本地模型快照", "正式模型发行", "激活",
            "模型包标识", "草稿修订号", "快照标识", "模型发行标识",
            "数据绑定", "源事实", "对象投影", "对象集合",
            "完整性", "准确度", "新鲜度",
            "业务动作", "外部回执", "结果核验", "执行结果未知",
            "访问控制", "审查决议", "依赖锁", "审批内容锁定"
        ):
            with self.subTest(name=name):
                self.assertIn(name, {x[0].strip("*") for x in self.items})
        for word in ("Windows 11", "NOT RUN", "LinkFact", "SchemaRelease",
                     "SourceFact", "Action", "OFIP-0001", "F01–F29"):
            self.assertIn(word, self.doc)

    def test_relative_links_in_glossary_exist(self):
        failures = []
        for link in re.findall(r"\]\(([^)\s]+)\)", self.doc):
            if link.startswith(("https:", "http:", "mailto:", "#")):
                continue
            file = (GLOSSARY.parent / unquote(link.split("#")[0])).resolve()
            if not file.is_file():
                failures.append(link)
        self.assertEqual([], failures)

    def test_onboarding_references_glossary(self):
        for file in INTRO:
            with self.subTest(path=file.name):
                self.assertIn("砺元本体产品统一术语词汇表.md",
                              file.read_text(encoding="utf-8"))

    def test_glossary_explains_status_not_release(self):
        for phrase in ("不是另一份功能/技术架构", "模型结构定义", "未正式发布",
                       "不能", "当前阶段"):
            self.assertIn(phrase, self.doc)

if __name__ == "__main__":
    unittest.main()
