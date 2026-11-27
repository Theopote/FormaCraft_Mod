# Phase 02.3.1 — Building Shell Geometry (delivery report)

**Status:** complete (stop before Phase 02.3.2 floors)  
**Date:** 2026-11-27  
**Baseline:** `19df7be0bbae6b63aa9a4acd0288a7e4e8f2c2d2`

## Call chain

```
LlmPlan → MassCsgPeerFoldRepair → MassFootprintSeparationRepair (mover-auth)
       → ComponentEmitPass / BuildingGeometryContext.putMass (CSG once)
       → MassMainGenerator.emitBooleanComposite (shell or solid from domain)
       → BuildingVolume AABB (coarse only)
```

## Spatial domain contract

| Consumer | Source of truth |
|----------|-----------------|
| Occupancy / shell | `CsgResult.contains` / `shellWalls` / `footprintAt` |
| Coarse cull | `BuildingVolume` post-boolean AABB |
| Footprint / floors metadata | `ResolvedBuildingGeometry` (compat; floors not rebuilt this round) |

Parse-once: `BuildingGeometryContext.Entry.domain` + `domainOrCompose`.

## Performance

- Occupancy: packed `Set<Long>` O(1) contains
- Y-slices for `footprintAt` / horizontal boundary
- `carveVoids` recorded at SUBTRACT (no AABB blast)
- Shell: one O(N) walk after 4-neigh erode

## Tasks

| Task | Result |
|------|--------|
| A Indexed CsgResult | Done |
| B Domain cache | Done |
| C Strict peer op + host shape gaps | Done |
| D Boundary helpers | `isSolidSurface`, `horizontalBoundaryAt`, `shellWalls` |
| E Wall shell emit | hollow erode / solid fill / `E_MASS_WALL_THICKNESS` |
| F Mover-only separation | Done |
| G Samples | `MassShell0231Test` + composition unit tests |
| H Gates / docs | This report + inventory / authority map |

## Gaps

| Code | When |
|------|------|
| `E_MASS_WALL_THICKNESS` | Erosion empties domain under hollow |
| `E_MASS_CSG_SHAPE_UNSUPPORTED` | Non-box host/child under CSG |
| `E_MASS_CSG_OP_UNSUPPORTED` | Illegal `mass_operation` |
| `E_MASS_FOOTPRINT_CONFLICT` | Mover lacks separation auth |

## Test evidence

| Suite | Result |
|-------|--------|
| `MassShell0231Test` / `MassBooleanCompositionTest` | PASS |
| `MassHarden0221Test` / `MassCompositionSamplesTest` / Wing / Temple | PASS |
| FlatRoof* / Oct20* / MassBooleanSamples | PASS |
| `constraintParityTest` | PASS |
| `eval.ci_gate --quick` | PASS |

## Deferred

- Floor / storey plates → **02.3.2**
- Multi-peer interlock sets + shared-wall cleanup → **02.3.3**
- Non-box CSG boolean → later capability

## Design-authority audit

| Finding | Classification |
|---------|----------------|
| Index / erode / shell | GEOMETRY |
| Domain cache | GEOMETRY_EXECUTION |
| Mover auth | HARD_CONSTRAINT |
| Soft-annex mover hint | NONBINDING_HINT |
| Keyword typology | **not introduced** |

**Verdict:** PASS

## Stop line

**Request entry to Phase 02.3.2 — Floor Geometry & Storey Levels.**  
Do not start 02.3.3–02.4 in this delivery.
