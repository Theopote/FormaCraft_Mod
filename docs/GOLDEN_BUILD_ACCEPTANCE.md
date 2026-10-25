# Golden build acceptance checklist

Baseline: compiler CI stabilization after 793d9428 stair transition carve.
Automated coverage is listed first. Items that require a live Minecraft client are marked **In-Game Required**.

## Root-cause summary (CI #40188059063)

| Bucket | Codes | Shared fix |
|---|---|---|
| CIRCULATION_FAIL | E_PLAN_CIRCULATION_CONFLICT | Plan-level flight collapse (later tread overrides earlier clearance); capture last-write-wins |
| CIRCULATION_FAIL | E_CIRCULATION_EXTERIOR_CONFLICT | Floor/ceiling clearance envelope skip; exterior connectorAccess clearHeight+1 at door/roof/platform neighborhoods |
| PATCH_CONFLICT | E_PATCH_CONFLICT | STAIR_SYSTEM/STAIRCASE carve writers; exterior stair join for roof + corridor_enclosure |
| COMPONENT_GEOMETRY_LOSS | (none this batch) | — |
| POSTPROCESS_FAIL | (none this batch) | — |
| THRESHOLD_ONLY | (none; failures were empty patches at L70) | Kept size>1000 as coarse floor; semantic checks elsewhere |

## Golden cases (compile / preview / apply)

| # | Class | Automated | In-Game Required |
|---|---|---|---|
| 1 | Multi-storey house with interior stair | `fidelityInteriorStairLandingWalkableSample`, `StairWholeBuildingCirculationTest` | Preview vs Apply landing headroom; player climb |
| 2 | Raised tower + exterior connector stairs | `Oct18BuildingGeometryTest`, `StairWholeBuildingCirculationTest` (oct18_3) | Preview/Apply doorway width; Undo restores shell |
| 3 | Workshop / corridor enclosure approach | ComplexBuildRetest `oct17_late_4` | Glass enclosure opening looks intentional |
| 4 | Reception–research–tower compound | ComplexBuildRetest oct18_* | Terrain pad under connectors |
| 5 | Open pavilion (no walls) | existing pavilion facade tests | In-Game: no phantom walls after Apply |
| 6 | Earth-house / typology with stairs | ComplexBuildRetest latest_* | In-Game: storey reach |
| 7 | Special BlockState stairs (facing/half) | AssemblyCirculationOpsTest | In-Game: collision shape matches preview |
| 8 | Water / slope site | — | **In-Game Required**: foundation strip + stair start on uneven ground |

## Preview / Apply / Undo (In-Game Required)

- [ ] Confirm preview voxels match Apply for oct18 tower landing and interior house stair
- [ ] Cancel preview leaves world unchanged
- [ ] Undo after Apply restores every BlockState (including stair facing)
- [ ] Re-Apply after Undo is identical to first Apply
- [ ] Neighbor updates do not refill stair clearance

## Incremental edit (backlog; not blocking this phase)

- [ ] “只改屋顶颜色” stays Patch mode (`IncrementalRequestIntent`)
- [ ] “加一层” / “加大窗户” / “删掉左侧附属” keep host mass

## Out of scope this phase

Style library expansion, new DSL, FBD promotion, unrelated UI refactors.