# 捕获构件的受控适配

手工捕获的结构是不可变的原件。AI 使用构件时，根据目标尺寸生成内存变体，不修改原件、不把临时变体写回构件库。

## 使用方式

1. 在构件捕获页完成选区、锚点、朝向和分类。
2. 展开「构件语义确认」，在「构件变化规则」中配置三个捕获坐标方向：
   - 锁定：保持原长；默认三个方向全部锁定。
   - 重复中段：保留两端，只重复或减少中间切片。
   - 缩短中段：只允许减少中段，不能加长。
3. 每轴的「边」按钮选择两端各保留 1、2 或 3 层方块。固定端之间必须至少留一层中段。
4. 默认禁止换材。打开「允许安全换材」后，生成请求可以启用语义换材。保存语义标签不等于开放换材权限。
5. 点击试算尺寸按钮，在原长、+1、+2、-1、-2 格之间切换，然后「校验并预览变体」。预览不改变世界；保存的仍是原件和变化规则。
6. 保存入库。目录与模型提示中会显示各方向的权限、尺寸范围及换材权限。

坐标 X/Y/Z 是捕获时的方块坐标；Y 是高度。东/西朝向的窗户，其正面宽度可能在 Z 方向。当前界面不自动猜测可变部位。

## 推荐配置

| 构件 | 建议开放的方向 | 固定区域 |
| --- | --- | --- |
| 柱 | Y 重复中段，X/Z 锁定 | 柱脚、柱头各 1–3 层 |
| 窗框 | 正面宽度方向和 Y 重复中段，厚度锁定 | 两侧框、窗台和顶部各 1–3 层 |
| 烟囱 | Y 重复中段，X/Z 锁定 | 底座和烟囱帽各 1–3 层 |
| 雕花、雕塑、复杂拱顶 | 全部锁定 | 使用不同尺寸的人工版本 |

重复中段会循环完整切片序列，并保留其中的空切片。目标不是对体素做分数缩放。3 格宽、两侧各占 1 格的窗框最小可用宽度为 3 格，不能硬缩成 2 格。

## 生成接口

既有 `component_request:` 特征中可以指定构件 ID 和查询：

```json
{
  "id": "captured_column",
  "component_query": {
    "semantic": {"role": "column"},
    "geometry": {
      "openingWidth": 3,
      "openingHeight": 9,
      "targetDepth": 3,
      "tolerance": 0,
      "scalable": true
    }
  },
  "semantic_skin": true,
  "semantic_style_id": "DEFAULT"
}
```

这里三个尺寸是目标构件的捕获坐标包围盒 X/Y/Z，字段沿用既有查询格式。`tolerance` 是允许的格数误差，默认 0；允许误差内优先原样使用，否则尝试规则允许的最近尺寸。`scalable=false` 保持原件尺寸。`approx_size` 兼容入口也支持宽、高、深（误差沿用既有 2 格）。

检索流程会尝试排名靠前的其他候选，拒绝不符合尺寸权限的构件，不通过旧检索路径绕过失败检查。无安全适配时返回无构件，调用方沿用已有的程序生成/空结果处理。

## 保留与校验

- 分段适配按 X、Y、Z 顺序累积，精确控制最终切片数，不再次执行整体放大或外侧裁剪。
- 锚点保持原点，内外/上下标记、宿主面和插槽原点跟随坐标映射；插槽尺寸对应更新，原始元数据保持独立。
- **第三十六批**：方块重建与 Socket/附着点共用切片平铺映射（首个平铺实例），禁止比例插值与周期重复分叉。
- **第三十七批**：`applyRuntimeVariant`/`fitToQuery` 对 `CAPTURED`（及有 `variantRules`）统一门禁，无规则时禁止 `runtimeVariant` 整数放大；`VariantCompileResult` 区分 TEMPLATE_MISSING 等硬失败；`SocketPlacement.adaptMode`（FIXED/STRETCH/REPEAT/FORBIDDEN）；缩略图缓存 LOADING/READY/FAILED + `resourceEpoch`；预览与建造 Patch 多重集合一致。
- **第三十八批**：检索 `ComponentResolveResult`（NO_CANDIDATE 才可程序化降级；ADAPT_FAILED/LOAD_FAILED 硬失败）；`ComponentGeometryResolver`/`ResolvedComponentGeometry` 作为预览与 Expander 唯一 PLACE 权威；carve 与 PLACE 共用变换，mount carve 受 socket 包络约束。
- **第三十九批**：挂载操作原子化（宿主/清洞/安装件全成或全败）；`mount_required` 默认禁止兼容失败 onlyHost；`ResolvedCarveVolume` 校验包络与保护结构；`ComponentGenerationOutcome` 上送诊断；空 Patch 默认失败（`empty_geometry_ok` 除外）。
- **第四十批**：Router/Hub/Compiler 贯通硬失败（禁止回退普通几何）；`BlockEntry.carveRole` 与 Socket AABB 包含性；可选挂载 `PARTIAL_SUCCESS`；必需生成器异常门禁。
- **第四十一批**：授权清洞体积（宿主 socket 原点 + size.max，与请求解耦）；包络类型显式 `component_request` 硬冲突 `E_COMPONENT_REQUEST_CONFLICT`；必要类型空 Patch 门禁；库成功跳过 typology 覆盖。
- **第四十二批**：组展开 `tryExpandResult`（必要子硬失败 / 可选挂载 PARTIAL）；权威路由先库后生成器；编译 Patch 事务提交；`OpeningRegion` 表达可清洞格（size 仅兼容约束）。
- **第四十三批**：`GroupComponentEntry.required` 默认 true；组几何仅 `ComponentGeometryResolver.emit`；OpeningRegion 显式 cells 不与盒并集；编译期/后处理 Patch 冲突门禁 `E_PATCH_CONFLICT`。
- 重复/裁剪带中包含门的上下半块、床的头尾等成对对象时拒绝变形，避免切断功能结构；**保存前**与运行时使用同一套配对方块扫描。
- 捕获保存写入 `sourceOrigin=CAPTURED`；`VariantGenerator` 对捕获件（及带 `variantRules` 的定义）禁止随机缩放/裁剪/镜像，仅走确定性 `SegmentScaler` / `ComponentModelApi`。
- 单轴最大 256 格，变体最多 262144 个方块。
- 换材保留楼梯、台阶、墙、栅栏、窗格等形状，以及适用的朝向、半块、轴向、含水等属性。没有兼容材料形状时保留原方块；门、床、活板门、方块实体和不支持的功能方块保留原样。多语义标签先过滤 `semantic_map` 命中项，再按 STRUCTURE/STRUCTURAL→FRAME→SURFACE→FILL→ORNAMENT→作者 `tag_priority`→sorted。
- `SocketPlacement.adaptMode`：缺省 STRETCH（单点映射）；REPEAT 沿 REPEAT 轴按平铺周期复制安装点（id 加 `#k`）；FORBIDDEN 落在可变中段则适配失败。
- 旋转和镜像使用 Minecraft 原生方块状态变换，包含原木轴向等属性。
- 保存端及既有健康检查共用变化规则校验。未包含 `variantRules` 的旧构件保持旧的规则来源与兼容路径。

## 当前边界

这一版支持轴向的两端固定、中段变化。两端厚度在捕获界面中对称设置；数据格式允许分别指定 `fixedStart` / `fixedEnd`。复杂的任意区域涂选、曲面贴合、自动推断可变部位、多样本拟合新构件、通用留空区域编辑与世界碰撞校验不是本次新增功能。安装仍使用现有的附着、插槽、开洞和建造预览流程。

游戏内验收重点：捕获一根带不同柱脚/柱头的柱子并拉长；捕获窗框并缩短宽高；捕获烟囱并拉高，再检查锚点、屋顶附着与预览。包含多方块功能结构或复杂装饰时优先锁定尺寸。
