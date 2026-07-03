# 生成质量参数参考（立面 / 轮廓 / 场地）

本文件记录「FormaCraft 生成质量总蓝图（三轨分阶段）」落地后新增的可控参数：它们**从哪里输入**、
**被谁消费**、**合法取值**，以及 `golden_eval.py` 如何对它们做回归。

> 约定：LLM 通过 `LlmPlan.components[].params`（对象）传参；键同时接受 snake_case 与 camelCase。
> 未被识别的取值会被生成器**静默忽略**（不报错），因此 eval 用 SOFT 断言提示「意图被浪费」。

---

## 一、组件立面参数（轨道 A）

写在单个组件的 `params` 里，作用于该组件（通常是 `MASS_MAIN` / 承重墙体）的立面。

| 参数 | 合法取值 | 消费者 | 行为 |
| --- | --- | --- | --- |
| `facade_profile` | `base_plinth` / `vertical_pilasters`(或`pilasters`) / `mullion_grid`(或`mullion`) / `module_grid` / `none` | `ComponentFacadeStyler.applyFacadeProfile` | 在墙面叠加构图：底部基座、竖向壁柱、竖挺网格、模块网格。可与 `wall_pattern` 叠加。 |
| `wall_pattern` | `gradient` / `striped` / `random` / `uniform` / `none` | `ComponentFacadeStyler.applyWallPattern` | 墙体材质随高度的分布：渐变 / 条纹 / 随机；`uniform`/`none` 为不改动。 |
| `facade_cutout` | `lattice`/`grille`/`perfor…` · `diagrid`/`diagonal`/`diamond` · `checker` · `rose`/`circle`/`oculus` · `arch…` · `solid`/`none` | `FacadePatternDsl.cellAt` | 在实墙上按 2D 图案镂空/花窗；`solid`/`none` 不镂空。Java 用 `contains` 匹配，故 `arch_window` 等复合词也有效。 |
| `assembly_facade` | 布尔（`true`/`false`，或 `1`/`0`/`auto`/`on`/`off`/`yes`/`no`） | `ComponentPlanCompiler.shouldUseAssemblyFacade` | 显式开/关宏观 Assembly 立面路径。缺省时按「风格族 + 规模 + 质量档」自动判定（见下）。 |
| `detail_level` | `low` / `medium` / `high`（别名 `quality` / `quality_level`） | `ComponentPlanCompiler.shouldUseAssemblyFacade` | `low` 时强制走简单立面路径；其它档位不阻止 Assembly。 |

### `assembly_facade` 的自动判定（未显式给出时）

`shouldUseAssemblyFacade` 依次判断：

1. 若 `params.assembly_facade`（或 `assemblyFacade`/`useAssemblyFacade`）显式给出 → 直接用它，强制开/关。
2. 否则若 `detail_level` 含 `low` → 关闭。
3. 否则要求规模足够：`min(width, depth) >= 6` 且 `height >= 8`，否则关闭。
4. 否则要求属于「宏观立面可表达」的风格族（`isMacroFacadeStyle`）：命中 `GOTHIC`/`CATHEDRAL`/`MODERN`/
   `INDUSTRIAL`/`CLASSICAL`/`CLASSIC`/`COLONNADE`/`COLUMN` 之一才开启；**中式/亚洲**（`CHINESE`/`HUI`/
   `JIANGNAN`/`ASIAN`/`中式`）显式排除，走各自的屋顶+立面推断路径。

### 示例

```json
{
  "component_type": "MASS_MAIN",
  "dimensions": { "width": 16, "height": 24, "depth": 12 },
  "params": {
    "facade_profile": "mullion_grid",
    "wall_pattern": "striped",
    "facade_cutout": "arch",
    "detail_level": "high"
  }
}
```

---

## 二、轮廓驱动楼板（轨道 C1）

**不是 LLM 参数**，而是用户画的轮廓（`OutlineShape`）自动生效。链路：

```
FormaRequest.getOutline()
  → LlmPlanPreviewBuilder（planSkeleton 分支）
  → PlanProgramCompiler.compileFromPlanSkeleton(..., outline)   // 5 参重载
  → PlanCompileContext.withOutline(outline)
  → StructuralExtractionStepV1.extract(..., context)
  → PlanSkeletonToStructuralSkeletonConverter.convert(skeleton, outline)  // 据多边形/圆出楼板
```

- 有轮廓：楼板贴合真实多边形/圆（顶点或半径）。
- 无轮廓（`getOutline() == null`）：退化为既有默认矩形楼板，行为不变。
- 旧的 4 参 `compileFromPlanSkeleton` 仍存在，内部以 `outline=null` 委托，**既有调用方零影响**。

---

## 三、场地地块排布（轨道 C3，opt-in）

在 `office_district` 的 `spec.extra` 里开启，把集群包围盒当作场地轮廓，用
`ParcelSubdivision` 切地块、`SiteLayoutPlanner` 做「大单体配大地块」的确定性排布。

| `extra` 键 | 取值 | 说明 |
| --- | --- | --- |
| `layoutMode` | `parcel` | 开启地块排布（其它值/缺省 → 走既有采样式 `CandidateGenerator + PlacementSolver`）。 |
| `siteLayout` | `true`/`1`/`yes`/`on`/`parcel` 等真值 | 与 `layoutMode=parcel` 等效的开关。 |

行为：

- 确定性、无随机；地块间距 `road = spacing - max(blockWidth, blockDepth)`。
- 切不出地块（轮廓过小）时**自动回退**到采样式 solver，保证不劣化。
- 产出与既有集群管线一致的 `BuildingPlacement`（`originRel` 为 footprint 最小角），下游地基/地形贴合逻辑不变。

> 现阶段 C3 仅接入 `OfficeDistrictGenerator`（同构 units）；`CityBuilder`（异构 units）与
> 「真实用户轮廓接入集群」是后续可选扩展。

---

## 四、回归门（`python_backend/eval/golden_eval.py`）

离线对已捕获的 LlmPlan JSON 打分：`python -m eval.golden_eval --plans <dir>`（`--gate` 让 SOFT 也计入失败）。

与本文件相关的断言：

- **HARD `has_geometry`**：`components[]` / `layout.slots[]` / `plan_skeleton|plan_program` 三者有其一即通过
  （修复了 C1 planProgram 主路径被误杀的问题）。
- **SOFT `facade_params_valid`**：校验 `facade_profile` / `wall_pattern` / `facade_cutout` / `detail_level` /
  `assembly_facade` 取值是否为生成器可识别值（枚举/子串/布尔）。`facade_profile`、`facade_cutout` 用**子串**
  匹配以对齐 Java 的 `contains` 语义。
- **SOFT `plan_program_path`**：当几何来自 `plan_skeleton/plan_program` 且无 `components` 时，跳过组件级
  语义检查（入口/窗/屋顶/数量），仅记一条信息，避免噪声告警。

新增/修改立面枚举或场地参数时，请同步更新本文件与 `golden_eval.py` 里的 `_ALLOWED_*` 集合。
