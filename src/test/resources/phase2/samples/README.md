# Phase 2 — 六类建筑固定回归样本

视觉参考图仅作验收意图说明，**不是** FormaCraft 当前生成成果。

每个样本比较四层结果：

1. `prompt` — 用户自然语言描述
2. `plan.json` — 编译输入（种子 LlmPlan；后续可换成真实 LLM 落盘）
3. Preview — 编译后的最终方块集（digest / 位置+状态）
4. Apply — `WorldPatchTransaction` 写入假世界后的方块集

## 样本

| id | 中文 | 检查重点 |
|----|------|----------|
| `small_house` | 小型住宅 | 墙体、门窗、屋顶 |
| `modern_villa` | 现代别墅 | 玻璃幕墙、体块组合 |
| `medieval_tower` | 中世纪塔楼 | 竖向空间、楼梯 |
| `east_asian_pavilion` | 中式或日式楼阁 | 多层屋檐、复杂屋顶 |
| `cathedral` | 大型教堂 | 重复构件、复杂立面 |
| `apartment` | 多层公寓 | 楼层、开窗节奏 |

## 如何更新 golden

1. 跑 `Phase2BuildingSampleRegressionTest`
2. 若结构检查通过且观感可接受，把测试输出的 `previewDigest` 写入对应 `sample.json` 的 `goldenPreviewDigest`
3. 此后 digest 漂移即失败（不仅是方块总数）

## Phase 00 digest audit (2026-11-25 / `5fc17303`)

Structural / focus checks stayed green; only `goldenPreviewDigest` drifted. Digests were refreshed **after** functional suites (ComplexBuild / Live / ConstraintParity / landing / exterior) were green — not as a batch mask for failing geometry.

| id | old → new | Intentional cause (GEOMETRY / HARD_CONSTRAINT only) |
|----|-----------|------------------------------------------------------|
| `small_house` | `ce39d21b88c69110` → `28bedafe58bff6b5` | Solid punch windows recess (depth≥2); roof cells window-protected; post-process skips `protectedClearance` |
| `modern_villa` | `b69a8fdf1bbd74ab` → `d1069d8c7e7c485f` | Wide glass recess + flat-roof ownership (no façade punch through roof plate) |
| `medieval_tower` | `aea78d9c93af2f74` → `7f8b675f130e1b8b` | Tall punch openings recess; roof/clearance isolation in post-process |
| `east_asian_pavilion` | `1f8ef8ab0e36f4db` → `71fbb7c71a22fafb` | Multi-eave ROOF surfaces refuse window punches; planar seal face groups |
| `cathedral` | `4d72ebfb906e16a9` → `7e13eafa3ff7f78f` | Dense façade openings + roof protection / clearance-aware material variation |
| `apartment` | `bf97a8a50dec4581` → `4b3353f492b5f1da` | Regular fenestration recess + lighting/material variation write-scope |

No DESIGN_OVERRIDE and no BuildingSpec / preset substitution. Refresh command used only after the table above was filled from observed digests.

## Phase 3 质量门（屋顶 / 立面）

`Phase3RoofFacadeQualityTest` 在同一套样本上额外检查：

- 屋顶覆盖率（按高度上半带，避免把坡屋顶脊线误判为“未覆盖”）
- 坡屋顶轮廓（`riseSpan` / pitched profile）
- 立面开口数量与南向开窗节奏

## 注意

- 种子 plan 只保证**管线可跑通**与焦点启发式，不代表最终产品观感。
- Phase 3–4 应替换为更高保真 plan / BuildingSpec，并继续收紧 `checks` / golden digest。
- 已知生成约束：`plan_meta.design_requirements` 会作用到所有 MASS（含 SECONDARY）；窄塔硬化不应覆盖已显式规划的 `FACADE_WINDOWS`。
