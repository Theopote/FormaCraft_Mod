# Phase 02.3.2 — Floor Geometry & Storey Levels (delivery report)

**Status:** complete (stop before Phase 02.3.3 Multi-Mass Junction)  
**Date:** 2026-11-28  
**Baseline:** `90c2bc5e100af879a38a505df1ded762afc16871`

## Storey authority

| Field | Meaning | Owner |
|-------|---------|-------|
| `floor_height` / `ground_clear_height` | Storey **band** including one slab thickness | USER_EXPLICIT / AI_PLANNED |
| `slab_thickness` | Default **1**; clear-air = band − slab | TECHNICAL_DEFAULT when absent |
| `floorYs` | Slab **bottom** Y in **host-local** space | Derived (`ResolvedFloorLayout`) |
| Plan-space Y | `origin + slot + local Y` | GEOMETRY execution |
| Overflow | Locked/hard bands vs envelope → `E_FLOOR_LAYOUT_OVERFLOW`; soft unlocked height may grow | HARD_CONSTRAINT on sealed envelope; FORMAT_REPAIR grow when height unlocked |

Classification: storey semantics = FORMAT_REPAIR + HARD_CONSTRAINT; floor materials preserve USER_EXPLICIT / AI_PLANNED; domain floors / ADD-only union = GEOMETRY; soft-annex / COMPOUND role unchanged NONBINDING_HINT.

## Floor call chain

```
LlmPlan → MassCsgPeerFoldRepair → MassFootprintSeparationRepair (seam moverAuthorized)
       → BuildingGeometryContext.putMass (domain once)
       → MassMainGenerator.emitBooleanComposite
            → Floor(y) = DomainFootprint(y) − ProtectedVoids
            → shellWalls / solid + GeneratedSurfaceCapture (WALL / COURTYARD_INNER)
       → ComponentEmitPass.registerMassBuildingVolumes
            → BuildingVolume(AABB + immutable domain + origin + protectedVoids)
       → PostProcess (occupancyContains / domainContains; ThreadLocal closed)
```

## Domain sharing

| Path | Domain source |
|------|----------------|
| CSG / ADD-only unified | `MassBooleanComposition.compose` / `domainOrCompose` |
| Non-CSG L/U/courtyard | `domainFromFootprint(ResolvedFootprint, height)` |
| Protected voids | `carveVoids` + params `protected_voids` / `stair_voids` (stair systems deferred) |

ThreadLocal `BuildingGeometryContext` ends at emit; postprocess uses copied `BuildingVolume.domain`.

## Performance

- Neighbor / pack probes: out-of-range → false (no throw)
- Budget multiply: `Math.multiplyExact` / `addExact`
- Floors iterate `footprintAt(y)` slices (indexed), not full AABB scan

## Tasks

| Task | Result |
|------|--------|
| A Domain lifetime | `BuildingVolume` domain handoff; `boundsContains` / `domainContains` / `isProtectedVoid` |
| B Storey contract | `ResolvedFloorLayout` + `slab_thickness`; Python `resolved_geometry` parity; overflow gap |
| C Domain-section floors | CSG hollow floors; solid / legal empty; material FLOOR |
| D ADD-only unified shell | `needsCsg` true for nested `masses≥2` UNION-only |
| E 02.3.1 leftovers | Seam moverAuthorized; fold shape/`volume_geometry` gaps; pack/budget; surface capture |
| F Tests | `FloorGeometry0232Test` F01–F15; W04/W05/W07/W10; CPC + mover |
| G Gates / docs | This report + inventory / authority map |

## Gaps

| Code | When |
|------|------|
| `E_FLOOR_LAYOUT_OVERFLOW` | Authored `floor_count × bands` exceed envelope (no silent grow) |
| `E_MASS_WALL_THICKNESS` | Hollow shell erosion collapses domain |
| `E_MASS_FOOTPRINT_CONFLICT` | CONNECTION_SEAM mover unauthorized (non-COMPOUND / non-passage) |
| `E_MASS_CSG_*` | Fold illegal op / non-box `volume_geometry` |

## Test evidence

| Suite | Result |
|-------|--------|
| `FloorGeometry0232Test` (F01–F15 + CPC + mover) | PASS |
| `MassShell0231Test` (incl. W04/W05/W07/W10) | PASS |
| `MassHarden0221Test` / `FloorLayoutOverflowRepairTest` | PASS |
| `Oct20RetryReplayTest` / Live0023 adobe floors | PASS |
| FlatRoof / Lighting | PASS |
| `constraintParityTest` | PASS |
| Python `resolved_geometry` / shared_java_python | PASS (7) |
| `eval.ci_gate --quick` | PASS |

**Not run / notes:** full `python_backend/tests` suite not executed end-to-end (module path); Inkscape `python` on PATH lacks pytest — used `py -3`. No Golden Digest edits.

## Residuals → 02.3.3

- Multi-mass shared-wall / corridor junction cleanup
- Local corridor wall-fill diagnostics as hard fail
- Full stair systems (protected-void interface only this phase)
- Multi-peer interlock sets

## Design-authority audit

| Finding | Classification |
|---------|----------------|
| Domain floors / ADD-only UNION | GEOMETRY |
| BuildingVolume domain handoff | GEOMETRY_EXECUTION |
| Storey overflow / no envelope grow | HARD_CONSTRAINT |
| CONNECTION_SEAM mover auth | HARD_CONSTRAINT |
| Soft-annex / COMPOUND | NONBINDING_HINT (unchanged) |
| Keyword typology / preset fallback | **not introduced** |

## Stop line

**Request entry to Phase 02.3.3 — Multi-Mass Junction & Shared Walls.**  
Do not start 02.3.4–02.4 from this delivery.
