# Phase 01.9 Delivery Report — Compiler Contract Hardening

**Date:** 2026-11-26  
**Scope:** Phase 01.9 only (Tasks A–H). **Stop — Phase 02 not started.**  
**Baseline:** `master` / prior 01.8 tree.

## Goal

Harden compiler contracts without DESIGN_OVERRIDE: roof opening authority, Oct20 repaired success, aesthetic ABSENT/DISABLED, lighting AABB opt-out, incremental edit Ω, path BlockState fidelity, soft-recover authority, postprocess WriteAttribution.

## Design-authority audit (skill)

| Finding | Classification | Notes |
|---------|----------------|-------|
| Flat-roof coverage exemption = ledger opening roles + circulation only | HARD_CONSTRAINT / GEOMETRY | `openingCarvable` never seals holes |
| Host-scoped opening authority | HARD_CONSTRAINT | Multi-building isolation |
| Aesthetic ABSENT/VALID/DISABLED/INVALID | HARD_CONSTRAINT provenance | STYLE_DEFAULT only ABSENT |
| Lighting opt-out by mass AABB | HARD_CONSTRAINT | Same-slot siblings not silenced by slot id |
| Incremental edit Ω / Δ⊆Ω | HARD_CONSTRAINT write isolation | Not style keywords |
| Path `BlockPatchTargetResolver.parse` | GEOMETRY fidelity | No road/A* redesign |
| Soft-recover hanging=true + authority lock | HARD_CONSTRAINT | No silent lantern redesign |
| Flat attach-plane keep vs MASS only | GEOMETRY | Pitched eaves still trim vs foreign ROOF |
| Postprocess attribution codes | FORMAT_REPAIR / diagnostics | No design substitution |

**Verdict:** PASS — no DESIGN_OVERRIDE introduced.

## Task matrix

| Task | Result |
|------|--------|
| A Roof opening authority | PASS — `FlatRoofCoverageValidatorTest` |
| B Oct20 repaired success | PASS — `Oct20AccessReplayTest`; unrepaired height=8 immutable gap |
| C Aesthetic ABSENT/DISABLED | PASS — `DesignDecisionPriorityTest`, `AestheticPassHintsTest` |
| D Lighting AABB + hanging + soft-recover | PASS — `LightingPostProcessorTest`, `PlanPatchConverterTest`, cross-building isolation fix |
| E Edit classes + Δ⊆Ω | PASS — `IncrementalEditScopeTest` |
| F Path parseState shared resolver | PASS — `PathSmartEmitAuthorityTest` |
| G E_POSTPROCESS_PATCH_CONFLICT ↔ WriteAttribution | PASS — `WriteAttributionTest` |
| H Full gates + report | See below |

## Commands / gates

| Gate | Result |
|------|--------|
| Focused 01.9 + ComplexBuild / Live0058 / Live2252 / ExplicitDesign / Oct20 / Phase2 | PASS |
| `scripts/check_architecture.py` | PASS |
| `scripts/check_design_authority_invariants.py` | PASS |
| `scripts/check_generation_entrypoints.py` | PASS |
| `./gradlew clean build` | **Partial:** `:clean` blocked by locked `build/pytest-vocab-loading` (Windows file lock). Used non-clean `./gradlew build` after clearing other build artifacts |
| `./gradlew build constraintParityTest` | **PASS** (`BUILD SUCCESSFUL` ~6m28s) — log: `reports/phase01_9_build_parity.log` |
| Python `pytest` (`python_backend`, Python 3.12) | **PASS** — 1313 passed, 1 skipped — log: `reports/phase01_9_pytest.log` |
| `python -m eval.ci_gate --quick` | **PASS** — golden / diversity / assembly_repair / constraint_parity / shape_fixtures — log: `reports/phase01_9_ci_gate.log` |
| `Phase2BuildingSampleRegressionTest` / `Phase2GoldenDigestCaptureTest` | PASS after documented golden refresh |

## Phase2 golden

| Sample | Old digest | New digest | Reason |
|--------|------------|------------|--------|
| `east_asian_pavilion` | `71fbb7c71a22fafb` | `0bdaf93985520472` | Intentional 01.9 fidelity: `hanging=true`-only soft-recover + authority-locked décor skip change lantern/support fingerprint. Focus heuristics still pass; not a silent redesign. |

No other Phase2 goldens rewritten. No success→expected-failure flips.

## Residuals / not executed

| Item | Status |
|------|--------|
| In-game preview / apply / local-edit soak | **Not executed** — no interactive Minecraft client in this agent session |
| Full `:clean` on Windows when `build/pytest-vocab-loading` is locked | Environment lock; non-clean `build` used |

## Inventory / map

- Updated `docs/architecture/DESIGN_DECISION_INVENTORY.md` (01.9 rows).
- Updated `docs/architecture/GENERATION_AUTHORITY_MAP.md` (opening / aesthetic / edit / attribution).

## Phase 01.9 verdict

**ACCEPT Phase 01.9.** All required automated gates green (with documented clean-lock workaround).

---

## Phase 01 Final Closeout (post-202f192)

Addresses acceptance residuals P1-01 / P1-02 / P1-03 only — no new architecture layer.

| Residual | Fix |
|----------|-----|
| P1-01 `check` vs `checkHost` authority | Shared `authorityForHost`; host-scoped openings on both paths; unhosted roofs get clearance only; emit indexes real host/building/component ids (no `#` truncation) |
| P1-02 lighting AABB mismatch | `BuildingVolume` gains `componentId`, `buildingId`, `lightingAllowed` at `ComponentEmitPass`; lighting reads the flag |
| P1-03 edit Ω not enforced | `patch.blocks` keep Ω even with MASS context; `PostProcessWriteScope.exactCells(Ω)` freeze; `deltaCells` for Δ⊆Ω |

Closeout tests: `FlatRoofHostAuthorityCompileTest`, `FlatRoofCoverageValidatorTest` host-scope cases, `LightingPostProcessorTest.lightingOptOutUsesCompileTimeIdentityNotAabbGuess`, `IncrementalEditScopeTest.patchModeLocalWallRecolorWithMassContextStillSuppressesAndBoundsOmega`.

### Closeout gates

| Gate | Result |
|------|--------|
| Focused closeout + Oct20 / Phase2 / CommunityGameProtocol | PASS (Community once flaked WAITING→READY; re-run green) |
| `constraintParityTest` | PASS |
| Python pytest | PASS — 1313 passed, 1 skipped |
| `eval.ci_gate --quick` | PASS |
| Architecture / design-authority / entrypoints | PASS |
| Full `:test` suite | 3664 tests; 1 transient `CommunityGameProtocolTest` flake unrelated to closeout; targeted re-run PASS |

**In-game soak:** not executed (no Minecraft client in this session).

**Phase 01 architecture governance: STOP.** Ready to apply for Phase 02 (building geometry) — do not expand further contract layers here.
