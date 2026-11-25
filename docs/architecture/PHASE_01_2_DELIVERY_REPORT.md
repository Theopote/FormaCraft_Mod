# Phase 01.2 Delivery Report — Local Edit Isolation

**Date:** 2026-11-25  
**Baseline:** Phase 01.1 complete on `c140072`+  
**Scope:** 01.2 only — P1-03 / incremental edit-scope contract. **Not** 01.3 seed freeze.

## Goal

Prevent door/window (opening) edits from expanding into whole-building aesthetic rewrites. Distinguish:

| Operation | Aesthetic expansion |
|-----------|---------------------|
| `mode=build` standalone façade / décor | Allowed (not an edit) |
| `mode=patch` opening / façade satellites | Suppressed |
| `mode=patch` bounded glass `patch.blocks[]` | Suppressed |
| `mode=patch` wall material recolor blocks | Not opening-class (separate; not suppressed via opening predicate) |

## Design authority

- **Decision affected:** post-process write isolation for incremental edits  
- **Owner:** Java GEOMETRY / HARD_CONSTRAINT (not style)  
- **Classification:** HARD_CONSTRAINT — no DESIGN_OVERRIDE; no keyword→building growth beyond the existing opening-type inventory moved into `IncrementalEditScope`

## Code

- Added `IncrementalEditScope` (`suppressesAestheticExpansion`, satellite + bounded `patch.blocks` predicates)  
- `PostProcessPipeline` delegates `isOpeningOnlyPatchPlan` → `IncrementalEditScope.suppressesAestheticExpansion` (**requires `mode=patch`**)

## Tests

| Suite | Result |
|-------|--------|
| `IncrementalEditScopeTest` | PASS (mode gate, outside-cell identity, build-mode contrast) |
| `PostProcessGeometryPreservationTest` / `LightingPostProcessorTest` | PASS (smoke) |
| Architecture scripts (`entrypoints` / `active_docs` / `design_authority_invariants`) | PASS |

## Docs

- `GENERATION_AUTHORITY_MAP.md` — P1-03 marked fixed; A-patch row updated  
- `DESIGN_DECISION_INVENTORY.md` — Phase 01.2 entry  

## Deferred (unchanged)

| ID | Round |
|----|-------|
| P1-04 postprocess PLACE-seed scope ratchet | **01.3** |
| P1-01 flat-roof perimeter provenance | **01.4** |
| P1-05 lighting hang-link atomicity | **01.5** |
| Path smart-emit hub routing | **01.6** |

Material-replace / balcony-add / room-expansion edit classes beyond opening isolation remain future edit-scope extensions — 01.2 establishes the `mode=patch` contract and opening proof.

## Go / no-go for Phase 01.3

**GO for 01.3** (freeze postprocess write scope / stop seed ratchet) after review of this report.
