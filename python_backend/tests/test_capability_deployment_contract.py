"""Capability-catalog deployment contract.

The exported Java catalog (``assets/formacraft/generation_capability_catalog.json``)
is the **only** authoritative capability contract. These tests lock:

1. The backend refuses to run degraded unless explicitly allowed.
2. The authoritative catalog is used **verbatim** — the Python static snapshot is
   never merged on top of it (that merge was the source of capability drift).
3. The static snapshot itself matches the Java table (no second truth source).
"""

from __future__ import annotations

import json
import os
import unittest
from pathlib import Path

from app.services import generation_capability as gc
from app.services.generation_capability import (
    CapabilityCatalogUnavailable,
    _FALLBACK_ALIASES,
    _FALLBACK_HOSTABLE,
    _FALLBACK_HOSTED,
    capability_mode,
    catalog_source_path,
    is_degraded,
    is_executable_roof_type,
    load_catalog,
    roof_aliases,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
JAVA_CATALOG = (
    REPO_ROOT / "src" / "main" / "resources" / "assets" / "formacraft"
    / "generation_capability_catalog.json"
)


class _EnvGuard(unittest.TestCase):
    """Restore module state and env after a test mutates them."""

    def setUp(self):
        self._env = dict(os.environ)
        self._catalog = gc._CATALOG
        self._source = gc._CATALOG_SOURCE
        self._paths = gc._catalog_paths

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._env)
        gc._CATALOG = self._catalog
        gc._CATALOG_SOURCE = self._source
        gc._catalog_paths = self._paths


class CapabilityDeploymentContractTest(_EnvGuard):
    def test_strict_mode_is_the_default(self):
        os.environ.pop("FORMACRAFT_CAPABILITY_MODE", None)
        self.assertEqual(capability_mode(), "strict")

    def test_missing_catalog_raises_in_strict_mode(self):
        """A standalone deployment must fail loudly, not silently degrade."""
        os.environ["FORMACRAFT_CAPABILITY_MODE"] = "strict"
        gc._catalog_paths = lambda: [Path("definitely-missing-catalog.json")]
        with self.assertRaises(CapabilityCatalogUnavailable) as ctx:
            load_catalog(force=True)
        message = str(ctx.exception)
        self.assertIn("REQUIRED deployment resource", message)
        self.assertIn("FORMACRAFT_CAPABILITY_MODE=fallback", message.replace("\n", " "))

    def test_missing_catalog_is_allowed_in_fallback_mode(self):
        """Development/test mode may use the static snapshot, and must be flagged."""
        os.environ["FORMACRAFT_CAPABILITY_MODE"] = "fallback"
        gc._catalog_paths = lambda: [Path("definitely-missing-catalog.json")]
        catalog = load_catalog(force=True)
        self.assertTrue(catalog.get("_fallback"))
        self.assertTrue(is_degraded())
        self.assertIsNone(catalog_source_path())

    def test_fallback_mode_is_explicitly_reported_as_degraded(self):
        os.environ["FORMACRAFT_CAPABILITY_MODE"] = "fallback"
        gc._catalog_paths = lambda: [Path("definitely-missing-catalog.json")]
        load_catalog(force=True)
        self.assertTrue(is_degraded())

    def test_invalid_catalog_is_rejected_not_silently_accepted(self):
        """A catalog with the wrong schema must not be used."""
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "generation_capability_catalog.json"
            bad.write_text(json.dumps({"schema": "wrong.schema.v9"}), encoding="utf-8")
            os.environ["FORMACRAFT_CAPABILITY_MODE"] = "strict"
            gc._catalog_paths = lambda: [bad]
            with self.assertRaises(CapabilityCatalogUnavailable):
                load_catalog(force=True)

    def test_schema_correct_but_incomplete_catalog_is_rejected(self):
        """Schema alone is not enough — planning-critical sections must be present."""
        import tempfile

        incomplete = {
            "schema": "formacraft.generation_capability.v1",
            "version": 1,
            "hostable_component_types": ["MASS_MAIN"],
            "hosted_component_types": ["ROOF"],
            "roof": {"aliases": {}, "supported_tokens": ["gable"]},
            # Missing component_types / typologies / building_classes
        }
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "generation_capability_catalog.json"
            bad.write_text(json.dumps(incomplete), encoding="utf-8")
            os.environ["FORMACRAFT_CAPABILITY_MODE"] = "strict"
            gc._catalog_paths = lambda: [bad]
            with self.assertRaises(CapabilityCatalogUnavailable) as ctx:
                load_catalog(force=True)
            self.assertIn("component_types", str(ctx.exception))

    def test_wrong_version_catalog_is_rejected(self):
        import tempfile

        wrong_version = {
            "schema": "formacraft.generation_capability.v1",
            "version": 99,
            "hostable_component_types": ["MASS_MAIN"],
            "hosted_component_types": ["ROOF"],
            "component_types": {"MASS_MAIN": {"implementation_status": "supported"}},
            "typologies": ["radial_terrace_hall"],
            "building_classes": {"generic": {}},
            "roof": {"aliases": {}, "supported_tokens": ["gable"]},
        }
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "generation_capability_catalog.json"
            bad.write_text(json.dumps(wrong_version), encoding="utf-8")
            os.environ["FORMACRAFT_CAPABILITY_MODE"] = "strict"
            gc._catalog_paths = lambda: [bad]
            with self.assertRaises(CapabilityCatalogUnavailable) as ctx:
                load_catalog(force=True)
            self.assertIn("unsupported version", str(ctx.exception))

    def test_invalid_implementation_status_is_rejected(self):
        import tempfile

        bad_entry = {
            "schema": "formacraft.generation_capability.v1",
            "version": 1,
            "hostable_component_types": ["MASS_MAIN"],
            "hosted_component_types": ["ROOF"],
            "component_types": {
                "MASS_MAIN": {"implementation_status": "maybe_later"},
            },
            "typologies": ["radial_terrace_hall"],
            "building_classes": {"generic": {}},
            "roof": {"aliases": {}, "supported_tokens": ["gable"]},
        }
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "generation_capability_catalog.json"
            bad.write_text(json.dumps(bad_entry), encoding="utf-8")
            os.environ["FORMACRAFT_CAPABILITY_MODE"] = "strict"
            gc._catalog_paths = lambda: [bad]
            with self.assertRaises(CapabilityCatalogUnavailable) as ctx:
                load_catalog(force=True)
            self.assertIn("implementation_status", str(ctx.exception))

    def test_explicit_catalog_path_override_is_honoured(self):
        """Standalone deployments point at the exported file explicitly."""
        os.environ["FORMACRAFT_CAPABILITY_CATALOG"] = str(JAVA_CATALOG)
        paths = gc._catalog_paths()
        self.assertTrue(
            any(str(p) == str(JAVA_CATALOG) for p in paths),
            f"env override must be a candidate path, got {paths}",
        )

    def test_strict_mode_does_not_reinject_missing_conditional_types(self):
        """Authoritative catalogs must not be widened by Python built-in snapshots."""
        import tempfile

        slim = {
            "schema": "formacraft.generation_capability.v1",
            "version": 1,
            "hostable_component_types": ["MASS_MAIN", "STRUCTURE"],
            "hosted_component_types": ["ROOF"],
            "component_types": {
                "MASS_MAIN": {"implementation_status": "supported"},
                "ROOF": {"implementation_status": "supported"},
                "STRUCTURE": {
                    "implementation_status": "typology_routed",
                    "planning_contract": {
                        "requires": "parametric structure_type only",
                        "routes": ["parametric_structure_type"],
                    },
                },
            },
            "typologies": ["radial_terrace_hall"],
            "building_classes": {"generic": {}},
            "roof": {"aliases": {}, "supported_tokens": ["gable"]},
        }
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "generation_capability_catalog.json"
            path.write_text(json.dumps(slim), encoding="utf-8")
            os.environ["FORMACRAFT_CAPABILITY_MODE"] = "strict"
            gc._catalog_paths = lambda: [path]
            catalog = load_catalog(force=True)
            self.assertFalse(catalog.get("_fallback"))
            conditional = gc.conditional_component_contracts()
            self.assertIn("STRUCTURE", conditional)
            self.assertNotIn("MODULE", conditional)
            self.assertNotIn("ASSEMBLY", conditional)

    def test_conditional_type_without_planning_contract_is_rejected(self):
        import tempfile

        bad = {
            "schema": "formacraft.generation_capability.v1",
            "version": 1,
            "hostable_component_types": ["MASS_MAIN"],
            "hosted_component_types": ["ROOF"],
            "component_types": {
                "ASSEMBLY": {"implementation_status": "fbd_or_empty"},
            },
            "typologies": ["radial_terrace_hall"],
            "building_classes": {"generic": {}},
            "roof": {"aliases": {}, "supported_tokens": ["gable"]},
        }
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "generation_capability_catalog.json"
            path.write_text(json.dumps(bad), encoding="utf-8")
            os.environ["FORMACRAFT_CAPABILITY_MODE"] = "strict"
            gc._catalog_paths = lambda: [path]
            with self.assertRaises(CapabilityCatalogUnavailable) as ctx:
                load_catalog(force=True)
            self.assertIn("planning_contract", str(ctx.exception))


class CapabilitySingleSourceOfTruthTest(unittest.TestCase):
    """The authoritative catalog must be used verbatim."""

    def test_authoritative_aliases_are_not_widened_by_python_fallback(self):
        """Regression: the Python fallback table was merged into the authoritative set.

        That merge let the planner emit roof types (``eave`` / ``dense_eaves`` /
        ``密檐`` / ``重檐``) that the Java ``RoofTypeCatalog`` cannot execute.
        """
        catalog = load_catalog(force=True, allow_fallback=False)
        java_aliases = (catalog.get("roof") or {}).get("aliases") or {}
        self.assertEqual(
            set(roof_aliases()),
            set(java_aliases),
            "roof_aliases() must return the authoritative alias set verbatim",
        )

    def test_python_only_eave_aliases_are_absent(self):
        for token in ("eave", "eaves", "dense_eave", "dense_eaves", "密檐", "重檐"):
            self.assertNotIn(token, roof_aliases())
            self.assertNotIn(token, _FALLBACK_ALIASES)

    def test_static_snapshot_matches_java_tables(self):
        """The development snapshot must not drift from the Java contract."""
        java = json.loads(JAVA_CATALOG.read_text(encoding="utf-8"))
        self.assertEqual(
            dict(java["roof"]["aliases"]),
            dict(_FALLBACK_ALIASES),
            "static fallback aliases drifted from the Java catalog",
        )
        self.assertEqual(
            set(java["hostable_component_types"]),
            set(_FALLBACK_HOSTABLE),
            "static fallback hostable set drifted from the Java catalog",
        )
        self.assertEqual(
            set(java["hosted_component_types"]),
            set(_FALLBACK_HOSTED),
            "static fallback hosted set drifted from the Java catalog",
        )

    def test_eave_tiers_are_not_a_roof_type(self):
        """Eave *tiers* live in params/typology, never as a fake roof_type."""
        self.assertFalse(is_executable_roof_type("dense_eaves"))
        self.assertFalse(is_executable_roof_type("密檐"))

    def test_catalog_loads_from_java_export(self):
        catalog = load_catalog(force=True, allow_fallback=False)
        self.assertEqual(catalog["schema"], "formacraft.generation_capability.v1")
        self.assertGreaterEqual(len(catalog.get("component_types") or {}), 40)
        self.assertIsNotNone(catalog_source_path())

    def test_planning_exposure_separates_supported_from_conditional(self):
        from app.services.generation_capability import (
            conditional_component_contracts,
            plannable_component_types,
            planning_component_exposure,
        )

        plannable = set(plannable_component_types())
        conditional = conditional_component_contracts()
        exposure = planning_component_exposure()
        self.assertIn("BRIDGE", plannable)
        self.assertNotIn("ASSEMBLY", plannable)
        self.assertEqual(conditional["ASSEMBLY"]["status"], "fbd_or_empty")
        self.assertIn("FBD", conditional["ASSEMBLY"]["requires"])
        self.assertIn("structure_type", conditional["STRUCTURE"]["requires"])
        self.assertIn("parametric_structure_type", conditional["STRUCTURE"].get("routes") or [])
        self.assertEqual(set(exposure["direct"]), plannable)
        # Unfiltered catalog contracts still include ASSEMBLY; request context may hide it.
        self.assertIn("ASSEMBLY", conditional)
        self.assertNotIn("MODULE", exposure["conditional"])


class CapabilityHealthEndpointTest(unittest.TestCase):
    """The deployment status must be observable from outside the process."""

    def setUp(self):
        # Ensure prior catalog path stubs do not leak into the ASGI app process state.
        gc._CATALOG = None
        gc._CATALOG_SOURCE = None
        load_catalog(force=True, allow_fallback=False)

    def _get(self, path: str):
        import asyncio

        import httpx

        from app.main import app

        async def scenario():
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as client:
                return await client.get(path)

        return asyncio.run(scenario())

    def test_health_capability_reports_the_authoritative_source(self):
        response = self._get("/health/capability")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["ok"])
        self.assertFalse(body["degraded"])
        self.assertEqual(body["schema"], "formacraft.generation_capability.v1")
        self.assertIsNotNone(body["source"])
        self.assertGreaterEqual(body["component_types"], 40)


if __name__ == "__main__":
    unittest.main()
