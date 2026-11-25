# Phase 01.1 Delivery Report — Design Authority & Compile Contract

**Date:** 2026-11-25  
**Baseline:** `c140072e` (Phase 00 CI Java `build` **success**)  
**Scope:** 01.1 only — no 01.2–01.8 implementations

## Gate

| Check | Result |
|-------|--------|
| HEAD / Phase 00 SHA | `c140072ee6a908b21b6fb17d97e272bed559b84b` |
| GitHub Actions Java `build` on that SHA | [success](https://github.com/Theopote/FormaCraft_Mod/actions/runs/45547608780) |

## Call-chain audit (01.1-A)

Default trunk (Path A) confirmed in code:

`LlmPlanPreviewBuilder.tryBuildPreview` → `ComponentPlanCompiler` / `PlanProgramCompiler` → `ComponentEmitPass` → `GenerationHub` / `UnifiedGeneratorRouter` → `PatchOccupancyIndex` + `SurfaceOwnershipLedger` → `PostProcessPipeline` → preview.

Documented in `GENERATION_AUTHORITY_MAP.md` (default trunk / compat / experimental matrix). No parallel compiler introduced.

## Authority inventory (01.1-B)

Consumer table added to `GENERATION_AUTHORITY_MAP.md`:

| Source | Priority | Consumers | Conflict |
|--------|----------|-----------|----------|
| Hard spatial / safety | HARD_CONSTRAINT | occupancy, surface ledger, envelope validators | gap / reject |
| USER_EXPLICIT / seals | USER_EXPLICIT | `DesignProvenance`, `ExplicitDesignPolicy`, style resolvers | `E_EXPLICIT_DESIGN_CONFLICT` / locked |
| AI_PLANNED | AI_PLANNED | same provenance path | keep; hard clash → gap |
| Geometry execution | GEOMETRY_EXECUTION | emitters / generators | realize authorized design |
| Aesthetic fill | STYLE_DEFAULT | `AestheticPassHints`, lighting/detail postpass | fill gaps only |
| `GenerationAuthority` | orthogonal | `GenerationCompileContext`, UGR | executor choice |

**No new Authority enum/context** — extended `DesignDecisionPriority` + docs only.

## Priority contract (01.1-C)

- Added `DesignDecisionPriority` (`HARD_CONSTRAINT` > `USER_EXPLICIT` > `AI_PLANNED` > `GEOMETRY_EXECUTION` > `STYLE_DEFAULT`).
- `AestheticPassHints` STYLE_DEFAULT fill gated by `styleDefaultMayFill`.
- User/AI vs hard constraint → `requiresCapabilityGap`; typology roof gate documents this (`TypologyRoofCapabilityValidator`).
- Design-authority skill applied: no DESIGN_OVERRIDE; no keyword router growth; no BuildingSpec silent fallback.

## Opt-out scope audit (01.1-D)

Plan / building / component scopes for `no_complex_decor`, lighting, roof/window/entrance/wall, façade relief documented in `GENERATION_AUTHORITY_MAP.md`.

**Deferred (register only):** P1-03 → 01.2, P1-04 → 01.3, P1-01 → 01.4, P1-05 → 01.5, path smart-emit → 01.6.

## Test contract restores (01.1-E)

| Test | Change |
|------|--------|
| `Oct20AccessReplayTest` historical plan2 | Single outcome: compile clean + non-empty after perimeter exemption |
| `FinalGeometryAcceptanceTest.roofTypeNone…` | Provenance + no ROOF satellite writer + `maxY < height` (drop redundant stairs assert) |
| `ComponentPlanCompilerDesignIntentTest` | Kept same-anchor compare; added per-building décor isolation vs sibling lighting |
| `StackedTowerFullCompileEnvelopeTest` | Kept envelope + `no_complex_decor`; added production-path lighting test without décor opt-out |

## New regressions (01.1-F)

`ExplicitDesignPriorityRegressionTest` + `DesignDecisionPriorityTest`:

- Explicit disable roof / windows / lights / open pavilion  
- Explicit material preserved  
- AI dimensions not overwritten  
- One-building `no_complex_decor` in two-building site  
- User vs hard constraint → diagnostic  
- Compile-context restore after failure  
- Cross-building param non-contamination  

## Inventory updates

- `docs/architecture/GENERATION_AUTHORITY_MAP.md` — call chain, consumer table, opt-out scopes, deferred IDs  
- `docs/architecture/DESIGN_DECISION_INVENTORY.md` — priority ladder + deferred residues  

## Go / no-go for Phase 01.2

| Criterion | Status |
|-----------|--------|
| No parallel trunk / scaffold-only types | Met |
| Explicit-design priority codified + tested | Met |
| P1-03 (`isOpeningOnlyPatchPlan` / `mode=patch`) registered, not fixed | Met — ready for **01.2** |
| Phase 00 CI green | Met |

**GO for Phase 01.2** after reviewer sign-off on this report and green local/CI verification of 01.1 changes.

## Tests executed (local)

| Suite | Result |
|-------|--------|
| `DesignDecisionPriorityTest` | PASS |
| `ExplicitDesignPriorityRegressionTest` (7) | PASS |
| `Oct20AccessReplayTest` (incl. historical plan2) | PASS |
| `FinalGeometryAcceptanceTest.roofTypeNone…` | PASS |
| `ComponentPlanCompilerDesignIntentTest` (incl. per-building opt-out) | PASS |
| `StackedTowerFullCompileEnvelopeTest` (incl. production-path lighting) | PASS |
| `LightingPostProcessorTest` | PASS |
| `scripts/check_generation_entrypoints.py` / `check_active_docs.py` / `check_design_authority_invariants.py` | PASS |
| Focused Python `test_ai_design_preservation` + `test_design_authority_regressions` | **65 passed** |
| Full `./gradlew build` / full Python 1313 | not re-run as whole-suite gate in this round; focused compile-path + architecture scripts green |

## Design-authority skill verdict

**PASS** — no DESIGN_OVERRIDE; no keyword router growth; no BuildingSpec silent fallback; USER_EXPLICIT / AI_PLANNED preserved; hard conflicts → `capability_gap`; STYLE_DEFAULT fill-only.

### Code delta summary

- `DesignDecisionPriority` + `AestheticPassHints` STYLE_DEFAULT fill gate  
- `LightingPostProcessor.lightingOptOutCells` (per-mass `lighting_style=none`)  
- Docs: `GENERATION_AUTHORITY_MAP`, `DESIGN_DECISION_INVENTORY`, this report  
- Tests restored + new regressions as above  

## Residual risks

- P1-01 perimeter opening provenance still uses `isOpeningLike` (01.4).  
- Opening-only postprocess skip still ignores `mode=patch` (01.2).  
- Postprocess PLACE-seed scope ratchet (01.3).  
