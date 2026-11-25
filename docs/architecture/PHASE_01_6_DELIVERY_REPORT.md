# Phase 01.6 Delivery Report — Unified Generation Routing (Path Smart-Emit)

**Date:** 2026-11-25  
**Scope:** 01.6 path smart-emit → GenerationHub authority. **Not** 01.7 quality-gate / write attribution.

## Goal

智能路径不再作为 `ComponentEmitPass` 预旁路；纳入 `GenerationHub` / `UnifiedGeneratorRouter` 权威门禁。显式构件库不被覆盖、BlockState 属性保留、硬失败不被旁路吞掉。

## Design authority

| Decision | Owner | Classification |
|----------|-------|----------------|
| When PATH A* may run | Java HARD_CONSTRAINT | Only `GenerationAuthority.COMPONENTS`, no explicit `component_request`/`group_request`, world + globalAnchor present |
| CAPTURED_LIBRARY / ASSEMBLY / SKELETON / TYPOLOGY | Existing hub contract | Unchanged — smart-emit never selected |
| Path geometry / materials | User/LLM plan params | Unchanged — no DESIGN_OVERRIDE |
| BlockState serialization | Java FORMAT_REPAIR | `BlockStateStringUtil.fromState` preserves properties |

## Code

| Change | Role |
|--------|------|
| `ComponentEmitPass` | Always `GenerationHub.generateComponentResult(semantic, world, globalAnchor)` |
| `GenerationHub` / `UnifiedGeneratorRouter` | Optional `globalAnchor`; COMPONENTS-gated `PATH_SMART_EMIT` |
| `ComponentPipelineStepId.PATH_SMART_EMIT` | Attribution for smart path commits |
| `LlmPlanPathSmartEmit` | Public hub helper; `serializeState` keeps facing/half/… |

## Tests

| Case | Result |
|------|--------|
| Stair BlockState properties preserved | PASS |
| CAPTURED_LIBRARY + explicit request → never `PATH_SMART_EMIT` | PASS |
| COMPONENTS PATH without world → registry, not smart step | PASS |
| Emit pass PATH compiles via hub (world-less) | PASS |
| `LlmPlanSiteTrunkTest` / `AuthorityRoutingContractTest` | PASS |

## Residual / follow-up

- Road width / waypoint / site A* geometry quality remains Phase 06.
- Phase 01.7: per-cell write attribution / quality-gate diagnostics.

## Go / no-go for Phase 01.7

**GO for 01.7** (final quality gate & diagnostics) after review.
