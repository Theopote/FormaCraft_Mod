# Phase 01.4 Delivery Report — Surface Ownership & Opening Provenance

**Date:** 2026-11-25  
**Scope:** 01.4 / P1-01 flat-roof perimeter provenance. **Not** 01.5 lighting atomicity.

## Goal

平屋顶覆盖豁免必须同时满足：**几何位置（檐口/周界）+ 显式开口授权**。不能仅凭 `REMOVE` / `glass` 关键词放行。

## Design authority

- **Decision affected:** flat-roof coverage hard gate vs authorized eave openings  
- **Owner:** Java HARD_CONSTRAINT / GEOMETRY (`FlatRoofCoverageValidator`, `SurfaceOwnershipLedger`)  
- **Classification:** HARD_CONSTRAINT — no DESIGN_OVERRIDE

## Code

| Change | Role |
|--------|------|
| `SurfaceOwnershipLedger.authorizedOpeningCells()` | WINDOW / DOOR / FACADE_OPENING / openingCarvable roof edge / circulation |
| `ComponentEmitPass` → `authorizedRoofOpenings` out-param | Publish provenance after emit |
| `ComponentPlanCompiler` | Merge circulation + ledger into `roofOpeningAuthority` for check/checkHost |
| `FlatRoofCoverageValidator` | Perimeter exemption only via `isAuthorizedPerimeterOpening` (needs authority set) |

## Tests

| Case | Result |
|------|--------|
| Unauthorized perimeter REMOVE → gap | PASS |
| Occupied perimeter glass still seals | PASS |
| L-shape inner-edge REMOVE without authority → gap | PASS |
| Ledger WINDOW + carvable roof edge authorize | PASS |
| Oct20 unrepaired → `E_FLAT_ROOF_*` | PASS |
| Oct20 plan2 repaired → still one unauthorized hole (documented) | PASS (expects gap) |
| Architecture scripts | PASS (run with suite) |

## Residual / follow-up

- `oct20_access_2_repaired.normalized.json` still has an unauthorized perimeter hole on `link_corridor#roof` at plan `(-8,7,-1)` that Phase 00 hid via `isOpeningLike`. Fixture geometry seal is deferred (not a silent redesign).  
- Broader 01.4 scenarios (skylight, floor intrusion, recessed glazing) rely on existing SurfaceOwnership / OpeningMaterialAudit / WindowSeal paths; this round focused P1-01.

## Go / no-go for Phase 01.5

**GO for 01.5** (lighting hang-link atomicity / décor support — P1-05) after review.
