# Phase 02.1 — Mass Geometry & Composition (delivery report)

**Status:** complete (stop before Phase 02.2)  
**Date:** 2026-11-26

## Task A — Production chain vs parallel BMC

| Path | Role | Authority |
|------|------|-----------|
| **Production** | `LlmPlan` → `ComponentPlanCompiler` → repairs (`MassFootprintSeparationRepair`, …) → `ComponentEmitPass` → `MassMainGenerator` / `ResolvedMassPart` | Live compile; design from user/LLM |
| **Parallel / experimental** | `com.formacraft.common.mass.BuildingMassComposition` (+ wing/cantilever helpers) | Inventory only — **not** wired into CPC; INTERSECT remains a stub. Do not route player builds through BMC |

No second compiler was introduced. CPC remains the single main path.

## Task B — 3D spatial classifier

New `MassSpatialRelation` (`SEPARATED` / `CONTACTING` / `XZ_OVERLAP_Y_DISJOINT` / `AABB3_INTERSECT` / `VERTICAL_STACK` / `INTENTIONAL_INTERLOCK`).

`MassFootprintSeparationRepair` only considers nudge candidates when relation is `AABB3_INTERSECT`. Half-open bounds; XZ-only / Y-disjoint stacks never conflict.

## Task C — Fidelity / opt-in separation

- Default: **do not** move authored peer masses on 3D collide.
- Soft annex (pavilion / side / wing tokens) may still nudge without plan-wide opt-in (preserves courtyard gardens).
- Otherwise require `allow_mass_separation` / `auto_layout` on `plan_meta` / `proportion_hints` / component params; else `E_MASS_FOOTPRINT_CONFLICT` via `AssemblyCompileDiagnostics`.
- Intentional interlock: `intentional_interlock` / `mass_composition` / free-text NONBINDING_HINT + `looksInterlockVolume`.
- `ResolvedComponentGeometry.normalizeBody`: stop silent min-3 inflation; preserve authored positive W/D/H (floor_height may still raise height).

## Task D — ResolvedMassPart stack cue

Auto-center / replace-envelope only when explicit params: `stack_mode=centered`, `replace_envelope`, `align=center`, **`stepped`**, `tier_footprints`, `setback_per_tier`. Contiguous Y alone is insufficient.

## Task E — Ten samples

`MassCompositionSamplesTest` S01–S10 (+ S09b stepped) and `MassSpatialRelationTest`.

## Task F — Regressions

| Suite | Result |
|-------|--------|
| MassSpatialRelation / MassCompositionSamples / ResolvedComponentGeometry / TempleGardenAlignment | PASS |
| FlatRoof* / Lighting* / Oct20* / MassCompositionSamples | PASS |
| `CommunityGameProtocolTest` | PASS this run |

**Known flake (registered):** `CommunityGameProtocolTest` has previously flaked `WAITING→READY` under load (Phase 01.9 delivery). Unrelated to mass geometry; re-run when observed. Do not “fix” by loosening protocol assertions.

## Golden / fixture notes

- `resolved-geometry.json` `small_envelope`: expected dims updated 3×3×5 → **1×2×5** (authored size preserved; height still from `floor_height`).
- Oct20 retry plan 6: stepped tower relies on authored `stepped=true` cue (not silent contiguous-Y centering).

## Design-authority audit (summary)

| Finding | Classification | Notes |
|---------|----------------|-------|
| AABB3-only conflict | GEOMETRY | Replaces XZ-only false positives |
| Opt-in / soft-annex / gap | HARD_CONSTRAINT + capability_gap | No silent primary-primary redesign |
| Soft annex id tokens | NONBINDING_HINT | Courtyard repair path only; never typology router |
| Interlock free-text | NONBINDING_HINT | Prefer `intentional_interlock` params |
| normalizeBody min-1 | GEOMETRY / provenance | Removes DESIGN_OVERRIDE min-3 |
| stepped / stack_mode cues | EXPLICIT_TEMPLATE / AI_PLANNED | Params, not building-name enums |
| BuildingMassComposition | EXPLICIT parallel inventory | Not production |

**Verdict:** PASS — no DESIGN_OVERRIDE introduced; production stays on CPC.

## Stop line

Phase 02.1 complete. **Do not start Phase 02.2** in this delivery.
