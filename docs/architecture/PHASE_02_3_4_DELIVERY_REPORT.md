# Phase 02.3.4 — Void & Spatial Protection (delivery report)

**Status:** implementation complete (stop before Phase 02.3.5)  
**Date:** 2026-11-28  
**Baseline:** `65203546b2aa883acae9c0693a1c77ab831175ea`

## Design authority

| Concern | Owner | Classification |
|---------|-------|----------------|
| Exact contact openings; ban AABB wall-delete | Java | GEOMETRY / HARD_CONSTRAINT |
| Pair-scoped grants (`JunctionGrantIndex`) | Java | HARD_CONSTRAINT |
| Geometry freeze + void resolve order | Java | HARD_CONSTRAINT lifecycle |
| UNSPECIFIED vs explicit INDEPENDENT | USER/AI | Intent semantics |
| Protected void kinds (courtyard/atrium/…) | USER/AI spatial use; Java = voxel range | Not building presets |
| Write matrix OMIT/CARVE/PLACE/PROTECT | Java | HARD_CONSTRAINT |
| Soft-annex / COMPOUND | unchanged | NONBINDING_HINT |

No keyword building routers, no DESIGN_OVERRIDE, no Golden Digest edits, no parallel geometry compiler.

## Contracts delivered

1. **Openings:** aperture ⊆ real contact keys; full width×height or `E_JUNCTION_OPENING_INSUFFICIENT_CONTACT` (no silent shrink / synthesize).
2. **Domain authority:** non-authoritative / unknown domain → no shared-wall omit, no open/passage carve, no AABB interlock grants.
3. **Pair grants:** `JunctionGrantIndex` keyed by `(aId,bId)` → cells/ops/provenance; empty registered set = deny; persist `plan_meta.compiler_junction_grants` and rehydrate at emit.
4. **Geometry freeze:** mass pose/size/thickness/storey mutators → freeze → `MassJunctionRepair` → `ProtectedSpaceRepair` → emit.
5. **Void model:** compact `protected_spaces` / compat `protected_voids` → plan-space cells + policy/allow-lists into `ProtectedVoidIndex`; kinds are spatial uses; CSG carve voids auto-protect when no descriptors.
6. **Consumers:** MassMain honors omit + carve REMOVE + floor PLACE + protect skip; EmitPass soft-clips unauthorized PROTECT hits; occupancy soft-omits; postprocess `isProtectedVoid` consults index.
7. **Assertions:** `BuildingSpatialAssertions` distinguishes passable (2-high air) vs walkable (+support); H21 uses passable topology (not `!stone_brick`).

## Gaps

| Code | When |
|------|------|
| `E_JUNCTION_OPENING_INSUFFICIENT_CONTACT` | Authored door exceeds continuous contact |
| `E_JUNCTION_INTENT_CONFLICT` | Pair intents disagree / multi-edge conflict |
| `E_PROTECTED_SPACE_HOST_MISSING` | `protected_spaces.host_id` not found |
| `E_PROTECTED_SPACE_EMPTY` | Descriptor resolves to zero cells |

## Test evidence

| Suite | Result |
|-------|--------|
| `VoidSpatialProtection0234Test` (V01–V25) | PASS |
| `MassJunctionHardening02331Test` (H01–H23) | PASS |
| `MassJunction0233Test` (J01–J18) | PASS |
| `FloorGeometry0232Test` / `MassHarden0221Test` / `MassShell0231Test` | PASS |
| `FlatRoofCoverageValidatorTest` / `LightingPostProcessorTest` | PASS |
| `Oct20*` / `ComplexBuildRetest` | PASS |
| `ConstraintParityTest` | PASS |
| `gradlew build -x test` | PASS |
| Python `pytest` (`python_backend/tests`) | PASS (1317) |
| `eval.ci_gate --quick` | PASS |
| `scripts/check_architecture.py` | PASS |
| `scripts/check_design_authority_invariants.py` | PASS |
| `scripts/check_generation_entrypoints.py` | PASS |

## Design-authority audit (summary)

| Finding | Classification |
|---------|----------------|
| Protected void kinds as spatial uses | USER/AI — not keyword presets |
| Exact openings / domain-auth deletes / pair grants | GEOMETRY / HARD_CONSTRAINT |
| Soft PROTECT clip (vs hard-abort whole compile) | HARD_CONSTRAINT with fail-soft generation |
| Soft-annex unchanged | NONBINDING_HINT |

**Verdict:** PASS — proceed to request Phase 02.3.5 when ready.

## Residuals → 02.3.5

- Full stair / vertical circulation planner
- Roof/facade systems beyond void interface
- Indoor auto-layout
