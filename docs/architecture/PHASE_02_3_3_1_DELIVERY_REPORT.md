# Phase 02.3.3.1 — Junction Geometry & Authority Hardening (delivery report)

**Status:** complete (stop before Phase 02.3.4 Void & Spatial Protection)  
**Date:** 2026-11-28  
**Baseline:** `f05b4c9c300865e4253416be94a5b0bdc1b0e907`

## Design authority

| Concern | Owner | Classification |
|---------|-------|----------------|
| Pair junction edges (`junctions` / `junction_by_peer` / scoped legacy) | USER_EXPLICIT / AI_PLANNED | Design intent — no peerless broadcast; conflict → gap |
| Open > passage > shared ranking | **removed** | Was DESIGN_OVERRIDE |
| Plan / slot / host-local transforms | Java | GEOMETRY / HARD_CONSTRAINT |
| Contact-face shared wall / through-thickness openings | Java (`MassJunctionGeometry`, `MassJunctionRepair`) | GEOMETRY |
| Cell-scoped interlock + `originalOwner` | Java (`PatchOccupancyIndex`) | HARD_CONSTRAINT |
| Soft-annex / COMPOUND | unchanged | NONBINDING_HINT |

No keyword building routers, no Golden Digest edits, no parallel geometry compiler, no silent locked moves.

## Contracts delivered

1. **Spaces:** contact/authority in plan-space; portal `relativePosition` = plan − slotAnchor; `junction_omit_cells` host-local (= plan − slot) matching generator `origin+voxel`.
2. **Pair intent:** `MassJunctionIntent.forEdge` reads plan/component `junctions`, then `junction_by_peer`, then legacy intent **only** when peer names the other; mutual conflict → `E_JUNCTION_INTENT_CONFLICT`; stamps go to `compiler_junction_by_peer` / `compiler_junction_edges`.
3. **Domain query:** AABB broad only; narrow = smaller-domain voxels + neighborhood; exact containment; empty ≠ null; unknown domain → no delete authority; compile-local domain cache; `ResolvedMassPart.syntheticEnvelope` + `solidParts()`.
4. **Openings:** contact regions → through-thickness OMIT + CARVE + floor PLACE; insufficient contact → `E_JUNCTION_OPENING_INSUFFICIENT_CONTACT`; floor continuity via `ResolvedFloorLayout.floorYs` + `slab_thickness`.
5. **Occupancy:** mutual peers **and** authorized cell set; `PlaceRecord.originalOwner` blocks A→B→C leakage.
6. **Lifecycle:** CPC fold → separation → floor overflow → footprint combo → **MassJunctionRepair** → enrichment; `invalidateDerived` for stale omit/portal.

## Gaps

| Code | When |
|------|------|
| `E_JUNCTION_INTENT_CONFLICT` | Mutual pair intents disagree (no open-wins) |
| `E_JUNCTION_ELEVATION_MISMATCH` | Passage/open with mismatched storey floors |
| `E_JUNCTION_PASSAGE_BLOCKED` | Sealed / narrow corridor (no silent shrink) |
| `E_JUNCTION_OPENING_INSUFFICIENT_CONTACT` | Authored door size exceeds continuous contact |

## Test evidence

| Suite | Result |
|-------|--------|
| `MassJunctionHardening02331Test` (H01–H23) | PASS |
| `MassJunction0233Test` (J01–J18) | PASS |
| `FloorGeometry0232Test` / `MassHarden0221Test` / `MassShell0231Test` | PASS |
| `FlatRoofCoverageValidatorTest` / `LightingPostProcessorTest` / `Oct20RetryReplayTest` | PASS |

## Design-authority audit (summary)

| Finding | Classification |
|---------|----------------|
| Pair-scoped junction edges | USER/AI design intent |
| Coordinate / omit / portal / through-opening | GEOMETRY |
| Cell + originalOwner occupancy | HARD_CONSTRAINT |
| Soft-annex unchanged | NONBINDING_HINT |
| Open-wins ranking | DESIGN_OVERRIDE — **removed** |

**Verdict:** PASS — proceed to request Phase 02.3.4 when ready.

## Residuals → 02.3.4

- Full void & spatial protection systems
- Complete stair / vertical circulation planner
- Roof/facade systems beyond junction openings
