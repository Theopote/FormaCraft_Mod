"""PR-B: CI gates for migrated culture cards and frozen generator registry."""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


def _repo_root() -> Path:
    here = Path(__file__).resolve()
    for p in [here] + list(here.parents):
        cand = (
            p
            / "src"
            / "main"
            / "resources"
            / "assets"
            / "formacraft"
            / "structural_typologies"
            / "structural_typologies_v1.json"
        )
        if cand.is_file():
            return p
    return here.parents[2]


def _assets_root() -> Path:
    return _repo_root() / "src" / "main" / "resources" / "assets" / "formacraft"


def _load_migration_map() -> dict[str, str]:
    path = _assets_root() / "structural_typologies" / "structural_typologies_v1.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    out: dict[str, str] = {}
    for card_id, entry in (data.get("migrationMap") or {}).items():
        if not isinstance(entry, dict):
            continue
        typ = str(entry.get("typologyId") or "").strip()
        if typ:
            out[str(card_id).strip()] = typ
    return out


def _llm_example_basenames() -> set[str]:
    root = _assets_root() / "llmplan_examples"
    names: set[str] = set()
    if not root.is_dir():
        return names
    for path in root.rglob("*.json"):
        names.add(path.name)
    return names


_REGISTER_RE = re.compile(r'register\s*\(\s*"([^"]+)"')


def _parse_registry_keys(registry_java: Path) -> list[tuple[int, str]]:
    lines = registry_java.read_text(encoding="utf-8").splitlines()
    found: list[tuple[int, str]] = []
    for i, line in enumerate(lines, start=1):
        m = _REGISTER_RE.search(line)
        if m:
            found.append((i, m.group(1).strip().lower()))
    return found


def _has_register_exemption(line: str, prev_line: str) -> bool:
    combined = f"{prev_line}\n{line}".lower()
    if "adr:" in combined or "// adr" in combined or "@adr" in combined:
        return True
    return "TypologyBackedStructureGenerator" in line


class PrBMigratedCultureCardContractTest(unittest.TestCase):
    MIGRATED = {
        "famen_pagoda": "dense_eaves_pagoda",
        "foguang_temple_hall": "tailiang_timber_hall",
        "giant_wild_goose_pagoda": "dense_eaves_pagoda",
        "temple_of_heaven": "radial_terrace_hall",
        "birds_nest_stadium": "stadium_bowl",
        "golden_gate_bridge": "suspension_bridge",
    }

    def test_migration_map_matches_expected(self):
        loaded = _load_migration_map()
        self.assertEqual(loaded, self.MIGRATED)

    def test_migrated_culture_cards_typology_contract(self):
        cards_dir = _assets_root() / "culture_cards"
        examples = _llm_example_basenames()
        migration = _load_migration_map()

        for card_id, expected_typology in migration.items():
            path = cards_dir / f"{card_id}.json"
            with self.subTest(card_id=card_id):
                self.assertTrue(path.is_file(), f"missing culture card {path.name}")
                card = json.loads(path.read_text(encoding="utf-8"))
                self.assertEqual(card.get("id"), card_id)
                self.assertEqual(card.get("structuralTypologyId"), expected_typology)
                self.assertNotIn("landmarkModuleId", card)

                refs = card.get("llmPlanExampleRefs") or []
                self.assertIsInstance(refs, list)
                for ref in refs:
                    self.assertIsInstance(ref, str)
                    low = ref.lower()
                    self.assertFalse(low.endswith("_module.json"), msg=ref)
                    self.assertNotIn("deprecated/", low)
                    self.assertIn(ref, examples, msg=f"missing llmplan example {ref}")


class PrBGeneratorRegistryFreezeTest(unittest.TestCase):
    def test_registry_keys_match_allowlist(self):
        repo = _repo_root()
        registry = (
            repo
            / "src"
            / "main"
            / "java"
            / "com"
            / "formacraft"
            / "server"
            / "generation"
            / "structure"
            / "router"
            / "StructureGeneratorRegistry.java"
        )
        allowlist_path = _assets_root() / "ci" / "generator_registry_allowlist.json"
        allowlist = json.loads(allowlist_path.read_text(encoding="utf-8"))
        allowed = {str(k).strip().lower() for k in allowlist.get("allowedKeys") or []}

        lines = registry.read_text(encoding="utf-8").splitlines()
        found: set[str] = set()
        for i, (line_no, key) in enumerate(_parse_registry_keys(registry)):
            found.add(key)
            if key in allowed:
                continue
            prev = lines[line_no - 2] if line_no >= 2 else ""
            line = lines[line_no - 1]
            self.assertTrue(
                _has_register_exemption(line, prev),
                f"unapproved register key '{key}' at line {line_no}",
            )

        self.assertEqual(found, allowed)


if __name__ == "__main__":
    unittest.main()
