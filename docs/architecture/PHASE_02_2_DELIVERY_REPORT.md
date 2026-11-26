# Phase 02.2 — Boolean Mass Geometry (delivery report)

**Status:** complete (stop before Phase 02.3)  
**Date:** 2026-11-26

## Authority boundary

| Path | Role |
|------|------|
| **CPC trunk** | `MassCsgPeerFoldRepair` → `MassMainGenerator` → `MassBooleanComposition` (box voxels + UNION/SUBTRACT/INTERSECT) |
| **BMC opt-in** | `BuildingMassComposition.allowsBlockAt` INTERSECT finished; still only via `use_building_mass` / `-Dformacraft.useBuildingMass` |

No silent BMC default. No building-name typology routers.

## Param contract (Task A)

- Nested `params.masses[]`: `operation` / `op` = `add|union` (default), `subtract|carve`, `intersect`.
- Peer: `mass_operation` + `host_id` / `target_host_id`, or `intentional_interlock` (+ optional `mass_composition`).
- Absent op → additive shell (02.1 behavior).
- Fold stamps `mass_boolean_composition` on host and `mass_csg_folded` on satellite.

## Implementation summary

| Task | Change |
|------|--------|
| B | `MassBooleanComposition` + `MassMainGenerator.emitBooleanComposite`; `CsgOp` accepts `add` |
| C | Separation skips boolean / folded peers; pipeline allows empty folded MASS |
| D | BMC INTERSECT: all INTERSECT masses must contain cell; gates ADD when present |
| E | `MassBooleanCompositionTest`, `MassBooleanSamplesTest`, `BuildingMassCompositionIntersectTest` |
| F | Authority map + inventory + this report |

## Gaps

| Code | When |
|------|------|
| `E_MASS_CSG_HOST_MISSING` | Peer `mass_operation` without resolvable host |
| `E_MASS_CSG_BUDGET` | Probe/result exceeds 250_000 voxels |
| `E_MASS_CSG_INVALID` / related | Malformed composite |

## Test evidence

| Suite | Result |
|-------|--------|
| MassBooleanComposition / MassBooleanSamples / BuildingMassCompositionIntersect | PASS |
| MassCompositionSamples / MassSpatialRelation / TempleGardenAlignment | PASS |
| FlatRoof* / Lighting* / Oct20* | PASS |

**CommunityGameProtocolTest:** registered unstable flake (`WAITING→READY`) — not re-run as blocker; unrelated to CSG.

## Design-authority audit

| Finding | Classification |
|---------|----------------|
| mass `operation` ADD/SUBTRACT/INTERSECT | GEOMETRY |
| ShapeLibrary-style CSG in MassMainGenerator | GEOMETRY_EXECUTION |
| Peer fold + empty satellite skip | HARD_CONSTRAINT provenance |
| BMC INTERSECT | EXPLICIT_TEMPLATE path only |
| Keyword typology / silent BMC | **not introduced** |

**Verdict:** PASS

## Residuals / Phase 02.3 entry

- Roof/facade junctions and shared-wall eaves boolean
- Pitched-roof CSG
- Facade rhythm CSG
- Do not grow `RequestIntentInjector` / `building_contract.py` for mass CSG

**Stop — do not start Phase 02.3 in this delivery.**
