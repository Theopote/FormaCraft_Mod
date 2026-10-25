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
2. `BuildRequestProcessor` / Python prompt are not yet forced through `BuildingEditService` as the sole PATCH path; callers must `prepareRoofMaterialReplace` → `issuePreview` → `bindPendingToTicket`. Apply SUCCESS commits revision via `BuildingEditPendingRegistry`.
3. Authorization is allow-list based (`allowedCells` = roof only); non-roof writes are rejected without enumerating the full AABB.

## 5. Suggested caller sequence (Phase 1)

```
targetQuery + materialIntent
  → BuildingEditService.prepareRoofMaterialReplace(...)
  → if ok: PatchPreviewService.issuePreview(patches...)
  → BuildingEditService.bindPendingToTicket(ticketId, prepared)
  → player Confirm → Apply SUCCESS → commitSuccessfulRoofEdit (automatic)
  → Cancel / failed Apply → pending dropped; Memory revision unchanged
```

