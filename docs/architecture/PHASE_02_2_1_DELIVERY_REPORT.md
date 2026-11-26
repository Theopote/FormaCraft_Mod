# Phase 02.2.1 — Mass Boolean Geometry Hardening (delivery report)

**Status:** complete (stop before Phase 02.3 / 02.3.1 wall shell)  
**Date:** 2026-11-27  
**Baseline:** `9a9a84d4b54036c928c60f5a78abb0e803c7c2aa`

## Authority boundary

| Concern | Classification |
|---------|----------------|
| INTERSECT empty / illegal op / budget | GEOMETRY |
| Plan-space fold via `originInPlan` | GEOMETRY |
| Consumable `CsgResult` → BuildingVolume | GEOMETRY_EXECUTION contract |
| CONNECTION_SEAM / separation scope | HARD_CONSTRAINT |
| Soft-annex / plan stepped text | NONBINDING_HINT with local lock |
| Pair interlock occupancy | HARD_CONSTRAINT |
| Full wall/floor from CSG domain | **deferred to 02.3.1** |

No DESIGN_OVERRIDE. No new keyword building routers. No `RequestIntentInjector` / `building_contract.py` growth.

## Defects closed

| ID | Fix |
|----|-----|
| P0-01 | INTERSECT seeds only when `!seeded`; empty after seed stays empty |
| P0-02 | `MassCsgPeerFoldRepair` Δ = `originInPlan(sat) − originInPlan(host)` |
| P0-03 | Emit registers post-boolean `CsgResult.bounds()`; folded satellites skipped |
| P1-01 | CONNECTION_SEAM requires opt-in / soft-annex GAP / passage link; nested thin ≠ seam |
| P1-02 | `intentional_interlock_peer_id` mutual pair in `PatchOccupancyIndex` (no component-wide overwrite) |
| P1-03 | `SteppedMassCueRepair` honors `stepped=false`, explicit `stack_mode`, eccentric XZ |

## Implementation summary

| Task | Change |
|------|--------|
| A | `MassBooleanComposition.compose` INTERSECT + `parseOpStrict`; legal empty host in pipeline |
| B | Plan-space fold; cycle/host/shape gaps |
| C | Extended `CsgResult`; `ComponentEmitPass.registerMassBuildingVolumes` |
| D | Scoped separation auth in `MassFootprintSeparationRepair` |
| E | Pair-scoped interlock occupancy |
| F | Stepped local opt-out |
| G | `MassHarden0221Test` + inventory / authority map + this report |

## Capability gaps

| Code | When |
|------|------|
| `E_MASS_CSG_OP_UNSUPPORTED` | Illegal `operation` (never silent UNION) |
| `E_MASS_CSG_HOST_MISSING` / `HOST_CYCLE` / `HOST_INVALID` | Peer fold binding failures |
| `E_MASS_CSG_SHAPE_UNSUPPORTED` | Non-box nested mass |
| `E_MASS_CSG_BUDGET` | >250000 voxels |
| `E_MASS_FOOTPRINT_CONFLICT` | Unlinked seam / AABB3 without opt-in |

## Test evidence

| Suite | Result |
|-------|--------|
| `MassHarden0221Test` (A–G compile-through) | PASS |
| `MassBooleanCompositionTest` / `MassBooleanSamplesTest` | PASS |
| `MassSpatialRelationTest` / `MassCompositionSamplesTest` | PASS |
| `WingSeparationDiagTest` / `TempleGardenAlignmentRepairTest` | PASS |
| FlatRoof* / Lighting* / Oct20* | PASS |
| `constraintParityTest` | PASS |
| `gradlew build -x test` | PASS |
| Python pytest | PASS (1314) |
| `eval.ci_gate --quick` | PASS (golden / diversity / assembly / parity / shapes) |

Phase2 golden digests: unchanged (no geometric digest rewrite required this round).

## Design-authority audit

| Finding | File | Classification | Test |
|---------|------|----------------|------|
| INTERSECT empty stays empty | `MassBooleanComposition` | GEOMETRY | a01–a05 |
| Fold via `originInPlan` | `MassCsgPeerFoldRepair` | GEOMETRY | b01 |
| Post-boolean BuildingVolume | `ComponentEmitPass` | GEOMETRY_EXECUTION | c01/c02 |
| CONNECTION_SEAM auth + soft-annex hint | `MassFootprintSeparationRepair` | HARD_CONSTRAINT / NONBINDING_HINT | d01–d03, S10b |
| Pair interlock occupancy | `PatchOccupancyIndex` | HARD_CONSTRAINT | e01/e02, S04 |
| Stepped local opt-out | `SteppedMassCueRepair` | FORMAT_REPAIR | f01/f02 |
| Keyword typology / silent HOUSE | — | **not introduced** | — |

**Verdict:** PASS

## Stop line

**Request entry to Phase 02.3.1 — Wall Shell from resolved CSG domain.**  
Do not start 02.3.2–02.4 in this delivery.
