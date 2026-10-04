# 建筑风格与材料检查

日期：2026-10-04。范围：重点为 components 编译入口；没有运行游戏或复现截图，不将潜在问题记为已验证的视觉故障。保留工作区已有屋顶修改。第一批实现见下方记录，其余条目保留审计时的发现。

## 结论

可以开始完善风格与材料。先修复材料解析与约束传递，再扩展风格外观规则。现有几何保护应继续保留；坡顶接缝、异形屋顶和最终需求验收仍未完成，不能宣称结构阶段已全部结束。

## 优先修复

1. **部分风格属性阻断预设回退。** `DynamicPaletteResolver.resolveWall/resolveRoof/resolveFloor` 在对应属性缺失或无法识别时仍返回石砖、云杉木板等通用默认值。`PaletteLibrary.resolveBlock`、`MassMainGenerator.getBlockForPart` 和 `RoofGenerator.getBlockForPart` 将这些值作为成功解析。因而现代风格只填写墙体属性时，屋顶会走云杉木板默认值，而非现代调色板。应区分“无对应属性”“无法解析”“成功解析”，无属性时继续按部位回退到风格预设；显式无法解析需有诊断。

2. **后处理会改变精确材料。** 默认 `PostProcessPipeline` 总是接入 `MaterialVariationPostProcessor`；后者不读取 context，直接按坐标把石砖、圆石、深板岩砖/瓦替换为苔藓或裂纹变体。`ExteriorIntegrityGuard` 只保护占用，不保护材料身份，无法阻止此替换。应传递材料来源和变体许可；精确材料默认锁定，风格推断材料才允许按策略做旧。

3. **逐栋材料支持不完整。** `MassMainGenerator.getBlockForPart` 优先读取 `wall_block`、`floor_block`，但 `RoofGenerator.getBlockForPart` 只读取全局属性和风格调色板。屋顶与墙体不共享材料角色优先级，逐栋屋顶材料尚需需求提取、宿主继承和生成器消费的完整链路。基座、柱、收边也应独立表达，避免 `wall_block` 同时覆盖 WALL_BASE/WALL_ACCENT 而抹平立面层次。

## 下一优先级

- **随机材料不可稳定重放。** `PaletteLibrary` 持有共享 Palette，`Palette.pick(part)` 使用实例内无种子 Random，主楼和屋顶使用此入口。虽然 `BlockPalette.pickWall/pickRoof` 已支持确定性选择，但 components 入口未统一使用它。应以设计种子、主体身份、部位和局部坐标决定选材，避免生成顺序改变结果。
- **风格名称与实际材料体系不一致。** `StyleIntentResolver` 能识别日式，但 `PaletteLibrary` 无日式映射，未知名称默认中世纪；工业映射现代。皇家调色板还含 `minecraft:red_bricks`，需要注册表验证，不能当作可用原版方块。风格别名、支持程度和材料库应统一登记，未知风格回退需可追溯。
- **风格补全混用缺省与禁用。** `StyleIntentResolver.isBlankOrNone` 将 `none` 视为缺省，并自动补屋顶、平面与装饰特征。此函数已接入编译器规范化及派生构件链路。是否最终违反禁用要求需结合需求契约进一步验证；此处应明确区分缺失、默认与显式禁用，并在每个补全入口执行约束。
- **传统房屋入口也存在材料族错配。** `HouseMaterialResolver.defaultRoof` 的 ASIAN 默认深板岩瓦，但 `defaultRoofStairs/defaultRoofSlab` 无深板岩瓦映射，会回退深色橡木楼梯/半砖。应统一整块、楼梯、半砖材料族，并为没有对应形态的材料声明近似策略。
- **构件模板材质映射范围偏窄。** `SemanticMaterialApplier` 只取首个语义标签，仅支持 wall/roof/floor 等字段，忽略 materialSet，文档示例 DOOR_FRAME 不会被解析。这是模板变体路线的问题，不宜混同为 components 主链路已发生的故障。

## 建议实施顺序与验收

第一批：修复部分属性回退、非法材料诊断和显式材料的后处理保护。验收现代风格仅填墙体属性、仅填屋顶属性、空属性对象、精确石砖墙以及无效方块 ID。

第二批：建立各生成入口共用的材料角色解析契约，补齐逐栋屋顶与宿主继承，统一材料族和确定性选择。验收两栋不同墙/屋顶材料、山墙继承墙材、屋檐/楼梯/半砖与屋面一致，以及相同输入和种子的完整方块结果一致。

第三批：集中完善少量风格族的比例、开间、窗型、屋顶和收边规则。优先木石住宅、现代平顶、徽派白墙黑瓦；只有规则与现有几何能力匹配后再扩展风格。审美需固定视角人工检查，不能用材料测试通过代替视觉验收。

## 第一批实施记录

- 动态材料解析在对应属性缺失时返回未解析，由生成器继续回退到风格预设；空属性对象不再让墙、屋顶、楼板、柱和装饰统一使用通用默认。修复 PaletteLibrary 对未解析结果的重复调用。
- 编译前校验全局材料名称和构件/嵌套参数中的 wall_block、floor_block、roof_block；不支持的材料使用 E_MATERIAL_INVALID 返回字段和值。生成出的方块目标也经共享严格解析器检查，避免无效目标被当作成功计划。
- 修复皇家调色板不存在的 red_bricks，并补齐现有示例使用的现实材料别名：red_lacquer → red_terracotta、white_marble → smooth_quartz、white_plaster/membrane → white_concrete、flat_slab → smooth_stone_slab、grey_tile/slate_tile → deepslate_tiles、dark_glazed_tile → black_glazed_terracotta。这些是 Minecraft 近似映射，不表示新增真实材质。
- 构件输出合并时记录实际匹配明确材料的方块位置，逐次合并更新，传入后处理上下文；做旧处理跳过这些位置，其他坐标不变的后处理也恢复这些位置的原始材料。地形适应仅作坐标变换，默认管道在其后没有处理器。
- 新增现代风格部分属性/空属性、非法材料、近似别名，以及全管道明确墙材保持的回归测试；保留未锁定材料的做旧能力。
- 验证：`gradlew.bat check --offline` 通过，552 项测试无失败；18 个 assembly 评测用例、43 份 assembly 示例、23 份文化卡和 37 个生成器注册键校验通过。未进行游戏内视觉验收。

边界：style_attributes 尚未区分用户明确输入与模型推断，本批保守保护其中材料字段所解析并实际输出的材料；按输出材料匹配锁定位置，未建立细粒度材料角色来源，也不新增变体许可开关。派生构件之间的材料继承和生成阶段的材料覆盖、逐栋屋顶消费、材料族、种子重放及禁用风格补全属于后续批次。后处理材料保护不等于整栋建筑材料需求验收。
