# Preview / Confirm / Undo 不变量（PR-4 冻结）

状态：维护中；核查：2026-11-15。  
**本轮不合并** `PreviewStorage`↔`PreviewTicket`，也**不合并** `UndoService`↔`PatchHistoryManager`。先冻结语义与回归，再谈统一存储。

权威细节仍见 [WORLD_MUTATION_CONTRACT.md](WORLD_MUTATION_CONTRACT.md)、[MEMORY_TRANSACTION_CONTRACT.md](MEMORY_TRANSACTION_CONTRACT.md)。

## 双栈对照（冻结）

| 项 | Build（结构） | Patch（增量） |
|---|---|---|
| 预览载体 | `PreviewStorage` + `PreviewMeta`（dimension / origin / digest / generation） | `PreviewTicket` / `PreviewTicketStorage`（dimension / final patches / quality） |
| 冻结应用集 | 确认前 snapshot 的 `GeneratedStructure`；digest = 最终格点状态 | Ticket 内 `ConfirmedPlanIdentity.toFinalPatches` 后的 patches |
| 确认入口 | `/forma_confirm` → `BuildConfirmService` | C2S → `PatchPreviewService.confirm` |
| 门禁顺序 | 有预览 → 世界 → **维度** → validate/digest → 质量门 → enqueue → **CAS generation consume** | 票据归属 → **维度** → 距离 → 质量门 → consume |
| 重复确认 | generation CAS：二次 confirm 无匹配 generation → 拒绝 | `consumeForApply` CAS：同 ticket 仅一次 |
| 质量门 | 共用 `BuildQualityGate`：FATAL 禁；ERROR 需 force | 同左（`QualitySnapshot`） |
| 写入 | `BuildTask` 分 Tick；失败/取消整批回滚（见 WORLD_MUTATION 第四十七批） | `WorldPatchTransaction` 同步；仅 SUCCESS 入历史 |
| 历史 | `UndoService` | `PatchHistoryManager` |
| 择栈 | `PlayerMutationLedger` + `MutationHistoryFacade`（时间倒序；丢弃幽灵账本条目） | 同左 |
| Memory | 仅完整 SUCCESS 建造可 `registerBuilding`；失败写入走 commit 重试/待保存，**不**靠删异常“修好” | Patch Diff Mutation；维度绑定 |

## 禁止事项

- 确认路径再跑一套与预览不同的过滤，或信任客户端上传的未过滤 patches。
- 把 `structure_after_fallback` / 假 BuildingSpec 回退接回确认链。
- 在 Memory 落盘与世界写入未原子前，合并两套 Undo 存储或假装已原子。
- 部分失败 / `ROLLBACK_INCOMPLETE` 记入成功 undo 栈或成功 Memory。

## 未决（PR-5+ / 实机）

- 统一预览 UX / 单一 ticket 模型。
- Memory↔世界真正原子事务。
- 跨栈 Undo 的实机幽灵条目与多人场景。

## 回归锚点

- `BuildConfirmServiceTest` / `BuildConfirmTransactionTest` / `PreviewConfirmApplyConsistencyTest`
- `PreviewTicketStorageTest`（CAS、维度、质量快照）
- `BuildTaskTest`（回滚 / `ROLLBACK_INCOMPLETE`）
- `PlayerMutationLedgerTest` / `BuildUndoMemoryTest`
- `PreviewUndoParityTest`（本批：双栈质量门与冻结集对等）
