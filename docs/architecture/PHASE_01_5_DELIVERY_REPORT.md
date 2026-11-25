# Phase 01.5 Delivery Report — Lighting & Décor Reliability

**Date:** 2026-11-25  
**Scope:** 01.5 / P1-05 hang-link atomicity + soft recovery. **Not** 01.6 path smart-emit routing.

## Goal

可选装饰失败不得落成悬浮石块；悬挂链与灯具必须成组提交/过滤。

## Design authority

| Decision | Owner | Classification |
|----------|-------|----------------|
| Unresolved décor recovery | Java HARD_CONSTRAINT (`PlanPatchConverter`) | No stone universal substitute; hangable semantic map or skip |
| Tall-storey hang fixture grouping | Java GEOMETRY (`LightingPostProcessor`) | Atomic `HangPair` — place / opt-out / scope both or neither |
| Lighting style / opt-out | User/LLM + existing `AestheticPassHints` | Unchanged — no DESIGN_OVERRIDE |

## Code

| Change | Role |
|--------|------|
| `PlanPatchConverter.softSubstitute` | `chain`→`iron_bars`, `end_rod`→`sea_lantern`, lantern/torch→legal fixture; flower/carpet/… → skip |
| Removed stone soft fallback | Optional décor no longer becomes `minecraft:stone` |
| `LightingPostProcessor.HangPair` | Ceiling hang-link + fixture commit and filter as one unit |

## Tests

| Case | Result |
|------|--------|
| Unresolved flower skipped (not stone) | PASS |
| Unresolved chain → iron_bars | PASS |
| Unresolved end_rod → sea_lantern | PASS |
| Tall storey hang link keeps fixture | PASS |
| Clearance on fixture suppresses link | PASS |
| Scope rejects fixture → drops whole pair | PASS |
| `StackedTowerFullCompileEnvelopeTest` | PASS |

## Residual / follow-up

- Broader compile-time atomic groups (door halves, railings, cantilever supports) remain out of this round; pattern is ready to reuse.
- Path smart-emit → GenerationHub authority is **01.6**.

## Go / no-go for Phase 01.6

**GO for 01.6** (path smart-emit hub routing) after review.
