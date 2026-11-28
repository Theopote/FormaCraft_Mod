# Phase 02.3.3 — Multi-Mass Junction & Shared Walls (delivery report)

**Status:** complete (stop before Phase 02.3.4 Void & Spatial Protection)  
**Date:** 2026-11-28  
**Baseline:** `960673954a20274bd47c9e61050ac3af85c231cc`

## Design authority

| Concern | Owner | Classification |
|---------|-------|----------------|
| Junction intent (`shared_wall` / `open_connection` / `passage` / `intentional_interlock` / `vertical_connection`) | USER_EXPLICIT / AI_PLANNED via explicit params | Design intent — not building-name presets |
| Contact geometry / shared-face extract / omit cells | Java (`MassDomainRelation`, `MassJunctionGeometry`, `MassJunctionRepair`) | GEOMETRY |
| Separation nudge vs junction cleanup | Prefer authorized junction handling; move only when still authorized | HARD_CONSTRAINT + GEOMETRY |
| Soft-annex / COMPOUND cues | unchanged | NONBINDING_HINT |
| Multi-peer interlock occupancy | `intentional_interlock_peer_ids` pairwise | HARD_CONSTRAINT |

No keyword building routers, no parallel geometry compiler, no silent locked-envelope moves, no auto-demolish of undeclared shared walls.

## Task A — 02.3.2 closeout (blocking)

| Item | Result |
|------|--------|
| A1 `needsCsg` lone SUBTRACT/INTERSECT | size≥1 with non-UNION op → true; illegal op → gap |
| A2 Floor Y | domain `[minY,maxY)`; authored `floorYs` unchanged by carve |
| A3 Non-CSG domain | nested `ResolvedMassPart` UNION (skip synthetic host envelope) |
| A4 Void write | `isProtectedVoid` in PLACE/CARVE; exterior pad face-adjacent only |
| A5 Storey + R01–R11 | `slab_thickness` non-CSG; ground_clear overflow formula; R01–R11 green |

## Junction call chain

```
LlmPlan → MassCsgPeerFoldRepair
       → MassJunctionRepair (intent × domain relation → omit/portal stamps)
       → MassFootprintSeparationRepair (skips shared/open/passage/vertical pairs)
       → MassMainGenerator (honors junction_omit_cells)
       → PatchOccupancyIndex (multi-peer mutual overwrite)
```

## Contracts

1. **Geometry ≠ design:** FACE_CONTACT alone never opens walls; corner/edge never invents shared wall.
2. **Shared wall:** lexicographically later id omits duplicate thickness; earlier owns material; seam outside shared face untouched.
3. **Open / passage:** openings only in authorized contact cells; elevation mismatch → `E_JUNCTION_ELEVATION_MISMATCH`; narrow corridor wall-fill → `E_JUNCTION_PASSAGE_BLOCKED`.
4. **Multi-peer:** A↔B and B↔C may coexist; A↔C denied without mutual naming.

## Gaps

| Code | When |
|------|------|
| `E_JUNCTION_ELEVATION_MISMATCH` | Horizontal passage/open with unequal `minY` |
| `E_JUNCTION_PASSAGE_BLOCKED` | `narrow_passage` + span ≤ combined wall thickness |
| `E_FLOOR_LAYOUT_OVERFLOW` | Locked envelope cannot fit ground_clear storey contract |
| `E_FLOOR_SLAB_CLEARANCE` | Enterable hollow with `slab_thickness ≥ band` |

## Test evidence

| Suite | Result |
|-------|--------|
| `FloorGeometry0232Test` (F01–F15 + R01–R11) | PASS |
| `MassJunction0233Test` (J01–J18) | PASS |
| `MassHarden0221Test` / `MassShell0231Test` | PASS |
| `FloorLayoutOverflowRepairTest` / Live0023 adobe floors | PASS |
| `FlatRoofCoverageValidatorTest` / `LightingPostProcessorTest` / `Oct20RetryReplayTest` | PASS |
| `constraintParityTest` | PASS |
| Python `pytest` (`python_backend/tests`) | PASS (1317) |
| `eval.ci_gate --quick` | PASS |
| `gradlew build` | PASS |

## Residuals → 02.3.4

- Full void & spatial protection systems beyond carve/`protected_voids` interface
- Complete stair / vertical circulation planner
- Deeper atrium write-scope hardening beyond exterior-pad fix

## Design-authority audit

| Finding | Classification |
|---------|----------------|
| `junction_intent` params | USER/AI design intent (explicit keys only) |
| `MassDomainRelation` / omit cells | GEOMETRY |
| Multi-peer interlock list | HARD_CONSTRAINT |
| Soft-annex / COMPOUND | NONBINDING_HINT (unchanged) |
| Keyword typology / preset fallback | **not introduced** |

## Stop line

**Request entry to Phase 02.3.4 — Void & Spatial Protection.**  
Do not start 02.3.5–02.4 from this delivery.
