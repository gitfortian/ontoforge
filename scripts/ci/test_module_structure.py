"""Production module boundaries and migration ownership, checked without a database."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ALLOWED = {
    "common": set(),
    "configuration": {"common"},
    "core": {"common"},
    "dao": {"common"},
    "platform": {"common", "configuration", "dao"},
    "business": {"common", "configuration", "core", "dao", "platform"},
    "boot": {"common", "configuration", "core", "dao", "platform", "business"},
}


class ModuleStructureTests(unittest.TestCase):
    def test_reactor_has_real_owned_modules_and_no_dependency_cycle(self):
        parent = ET.parse(ROOT / "pom.xml")
        modules = [node.text for node in parent.findall("m:modules/m:module", NS)]
        self.assertEqual(set(modules), {"ontoforge-" + name for name in ALLOWED})
        graph = {}
        for module in modules:
            folder = ROOT / module
            self.assertTrue(list((folder / "src/main").rglob("*.java")), module)
            pom = ET.parse(folder / "pom.xml")
            deps = {
                dep.findtext("m:artifactId", namespaces=NS).removeprefix("ontoforge-")
                for dep in pom.findall("m:dependencies/m:dependency", NS)
                if dep.findtext("m:groupId", namespaces=NS) == "io.ontoforge"
                and dep.findtext("m:scope", namespaces=NS) != "test"
            }
            name = module.removeprefix("ontoforge-")
            self.assertLessEqual(deps, ALLOWED[name], module)
            graph[name] = deps
        while graph:
            leaves = {name for name, deps in graph.items() if not deps}
            self.assertTrue(leaves, f"Cyclic production dependencies: {graph}")
            graph = {name: deps - leaves for name, deps in graph.items() if name not in leaves}

    def test_production_references_respect_module_direction(self):
        for name, allowed in ALLOWED.items():
            for file in (ROOT / f"ontoforge-{name}/src/main/java").rglob("*.java"):
                text = file.read_text(encoding="utf-8")
                references = set(re.findall(r"\bio\.ontoforge\.(\w+)\.", text))
                self.assertLessEqual(references, allowed | {name}, str(file.relative_to(ROOT)))
                if name in {"common", "configuration", "core"}:
                    self.assertNotRegex(text, r"import (?:org\.springframework|java\.sql|javax\.sql)\.")
                if name in {"dao", "platform", "business"}:
                    self.assertNotIn("org.springframework.web.bind.annotation", text)

    def test_boot_controllers_do_not_own_sql_or_transactions(self):
        for file in (ROOT / "ontoforge-boot/src/main/java/io/ontoforge/boot/controller").glob("*.java"):
            text = file.read_text(encoding="utf-8")
            self.assertNotRegex(text, r"\bJdbcTemplate\b|@Transactional|java\.sql\.")

    def test_migrations_have_one_owner_and_unique_versions(self):
        migrations = [file for folder in ROOT.glob("ontoforge-*/src/main/resources/db/migration")
                      for file in folder.glob("V*__*.sql")]
        self.assertTrue(migrations)
        versions = []
        for file in migrations:
            self.assertEqual(file.relative_to(ROOT).parts[0], "ontoforge-dao")
            versions.append(file.name.split("__", 1)[0])
        self.assertEqual(len(versions), len(set(versions)))


if __name__ == "__main__":
    unittest.main()
