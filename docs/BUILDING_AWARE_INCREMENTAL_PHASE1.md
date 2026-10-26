# Building-Aware Incremental Editing — Phase 1

Baseline: `bd4ae4b439ea` · 2026-10-25

## 1. Audit — player input → Memory update

| Stage | Code | Authority today | Gap for incremental edit |
|---|---|---|---|
| Intent mode | `IncrementalRequestIntent`, `PromptModeResolver` | Regex → PATCH vs BUILD | Mode only; no building identity |
| Request | `BuildRequestProcessor` | Forces PATCH on incremental wording | Still no target UUID binding |
| Prompt context | `PromptMemorySections` | Nearby memory text for LLM | Hint only; not a credential |
| Plan / patches | LlmPlan → `BlockPatch` | LLM may emit global writes | No server scope gate |
| Preview / Apply | `PatchPreviewService`, `PreviewTicket` | Expected world states, undo history | Reusable as-is |
| Impact / Memory | `PatchDiffAnalyzer`, `MutationBuilder`, `MemoryManager` | First spatial hit; absolute-Y `SemanticClassifier` | Attribution ≠ permission |

**Conclusion:** PATCH mode and Memory bounds exist, but LlmPlan patches are not server-bound to a building/part scope. Phase 1 adds that authority layer without a second world-write trunk.

## 2. Phase 1 deliverables

| Piece | Package / type |
|---|---|
| BuildingInstance | `com.formacraft.server.edit.BuildingInstance` |
| BuildingTargetResolver | explicit UUID → selection → unambiguous spatial; else refuse |
| BuildingPartIndex (roof) | provenance / persisted cells — **never** world absolute Y |
| BuildingEditScope + Validator | `ChangedCells ⊆ AllowedCells`; revision + expected states |
| Roof material replace | `RoofMaterialReplaceCompiler` + `ShapePreservingMaterialSwap` |
| Orchestration | `BuildingEditService.prepareRoofMaterialReplace` / `commitSuccessfulRoofEdit` |
| Registration hook | `BuildExecutionService` attaches roof index when `GeometrySource` marks roof |

## 3. Automated vs In-Game

| Requirement | Automated | In-Game Required |
|---|---|---|
| Single house roof replace | `BuildingAwareIncrementalPhase1Test` | Preview appearance |
| Two buildings; neighbor untouched | automated | Visual confirm neighbor |
| Ambiguous targets refuse | automated | UI shows refusal |
| Y≥100 roof identity | automated (part index) | — |
| Floors/walls unchanged | scope validator | Walk-through after Apply |
| Stair facing/half preserved | automated | — |
| Manual world edit before Confirm | world_conflict path | Preview conflict toast |
| Cancel preview | existing PreviewTicket | Checklist |
| Undo/Redo after success | existing PatchHistory | Checklist |
| Restart retains id/revision | Memory metadata (+ storage) | Restart server |
| Unregistered refuse | automated | — |
| Unauthorized wall cell refuse | automated | — |

## 4. Remaining risks

1. Many generators still omit `GeometrySource` on PlannedBlock/BlockPatch → new builds may register **empty** roof indices until provenance is threaded through the compiler. Edits then correctly refuse rather than guess.
2. Authorization is allow-list based (`allowedCells` = roof only); non-roof writes are rejected without enumerating the full AABB.
3. Generators that still omit `GeometrySource` may leave empty roof indices until a successful roof-stamped compile/apply merges cells into Memory.

## 5. Wired caller sequence (Phase 1)

PATCH + roof-material intent（`RoofMaterialReplaceIntent`）在 `LlmPlanPreviewBuilder.tryBuildPreview` 中优先走服务端路径：

```
userMessage matches roof material replace
  → BuildingTargetResolver
  → capture live roof BlockStates
  → BuildingEditService.prepareRoofMaterialReplace
  → PatchPreviewService.issuePreview
  → bindPendingToTicket
  → Confirm/Apply SUCCESS → commitSuccessfulRoofEdit
  → Cancel / failed Apply → pending dropped; Memory revision unchanged
```

ROOF 构件在 `ComponentPlanCompiler` 提交 Patch 时盖上 `ROOF_COMPONENT` provenance；`PatchHistoryManager` 在 Apply 后按**最终落地 Patch（last-write-wins）**合并屋顶索引（覆盖/删除的屋顶格会移出索引）。

## 6. Round 57 — Confirm / lifecycle / fail-closed

| Item | Status |
|---|---|
| 57-01 Confirm 前 revision/scope/live 复核（`BuildingEditConfirmGate`） | done — before ticket consume / world write |
| 57-02/03 Pending 跟随 Ticket 过期/取消/断线；取消先校验所有权 | done — `PreviewTicketStorage` |
| 57-04 live 缺失 fail-closed | done — `BuildingEditScopeValidator` |
| 57-05 最终屋顶来源索引 | done — `BuildingPartIndex.fromFinalPatches` / `mergeFinalBatch` |
| Memory 更新失败不得报完整成功 | done — Confirm 路径 |

自动化：`BuildingAwareRound57Test`、`BuildingAwareIncrementalPhase1Test`、`RoofMaterialReplaceIntentTest`。

### In-Game checklist（CI 不能代替）

1. 自然语言「屋顶换成深色橡木」→ Preview 仅屋顶变色，墙/楼板不变  
2. Confirm 成功 → Undo 恢复材质；Redo 再应用；Revision +1  
3. 预览后手动改一块屋顶再 Confirm → 拒绝  
4. 同建筑开两个屋顶预览，先后 Confirm → 第二个 revision 拒绝  
5. Cancel / 断线后 Pending 不残留；他人 Ticket UUID 无法清掉自己的 Pending  
6. 重启后 Memory 仍有 buildingId / revision / roof cells  
7. （Round 58）建筑编辑 Ticket 在 Pending 丢失后 Confirm 必须拒绝，不得当普通 Patch 落地  
8. （Round 58）Memory 同步失败提示 `EDIT_MEMORY_SYNC_FAILED`，该建筑暂停增量编辑直至 Undo/修复  

## 7. Round 58 — Ticket 身份与 Memory 一致性

| Item | Status |
|---|---|
| 58-01 `PreviewEditKind` 不可变标记；BUILDING 缺 Pending 拒绝 | done |
| 58-02 `EDIT_MEMORY_SYNC_FAILED` 阻断 + `repairMemorySyncWithLiveWorld` | done |
| 自动化 | `BuildingAwareRound58Test` |

## 8. Round 59 — Memory 事务可靠恢复

| Item | Status |
|---|---|
| `MemoryManager.updateMemoryStrict` 显式成功/失败 | done |
| Confirm 前 `BuildingEditTransaction` PREPARED；失败不写世界 | done |
| WORLD_APPLIED → MEMORY_COMMITTED / RECOVERY_REQUIRED | done |
| 恢复前核对 live 世界（目标态 / 撤销态） | done |
| `edit_txns/` 独立于 ProjectMemory，跨重启可识别 | done |
| 自动化 | `BuildingAwareRound59Test` |

