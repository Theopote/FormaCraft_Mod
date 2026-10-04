# 多体块归属、外轮廓与附楼平屋顶（第四批）

日期：2026-10-04。本批接入已有 `MASS_MAIN.params.masses`，不引入新的生成入口。

## 归属和几何

一个 MASS_MAIN 仍对应一栋建筑及一个需求作用域，其 params.masses 是同一建筑的附加体块，不增加建筑数量。每项 dimensions 为体块自身尺寸，offset 相对主 MASS_MAIN 解析后的最小角点；y 同样相对主体底部。

```json
{
  "component_type": "MASS_MAIN",
  "relative_position": {"x": 0, "y": 0, "z": 0},
  "dimensions": {"width": 6, "depth": 6, "height": 8},
  "params": {
    "component_id": "house",
    "anchor_mode": "min_corner",
    "roof_type": "flat",
    "masses": [{
      "offset": {"x": 6, "y": 0, "z": 2},
      "dimensions": {"width": 4, "depth": 4, "height": 5}
    }]
  }
}
```

该建筑包围宽度为 10、深度为 6，但 `(x>=6,z<2)` 仍是 L 形缺口。主屋顶标高为 7，附楼屋顶标高为 4。体块共享同一 slot 坐标系；slot.anchor 与世界锚点按已有契约叠加。

Java `ResolvedMassPart` 和 Python `resolve_mass_parts` 共用上述规则；主体生成器直接读取这些解析尺寸和偏移，避免另写一套 nested masses 解析。主块 part_id 等于 component_id，附块为 `component_id#mass_N`，N 是原始 masses 列表的一基序号。无效项保持原有跳过行为，不重排其后体块的序号。身份在同一方案内稳定，列表重排会改变附块身份；尚不保证跨生成请求稳定。

## 外轮廓和需求检查

契约 `resolved_buildings[].multi_mass` 保存 parts、envelope_local 和 outline_local。包围范围使用半开区间。明确宽度/深度要求改为检查整栋多体块包围范围，要求记录 `dimension_subject=building_envelope`；楼层参数仍检查主体，附楼可采用较低的高度。

矩形体块的水平投影按矩形并集解析边界，移除重叠及相邻部分的内部边，合并同向共线段。边记录 direction、plane、start、end，方向采用 Minecraft 世界方向。此报告不是填满包围矩形的指令，也不生成任何方块。不同高度的体块仍投影到同一二维报告；边界可能包括内部空洞边界，不是已经排序的单一闭合多边形。

圆形、圆角、切角、庭院等非矩形规则，或超过 256 个体块时，只报告包围范围，footprint_status 为 bounds_only，outline_local 为空。不得把包围矩形当成这类形状的精确外轮廓。报告的 coverage_status 仍为 parameters_only，尚未验收最终墙体或屋顶。

## 实际编译改进

- 建筑后处理按每个体块的边界登记 BuildingVolume，不把附楼方块当作主矩形外的无归属方块，也不通过单个大包围盒填补 L 形缺口。
- 同一主 MASS_MAIN 的简单平屋顶已存在或已推断时，编译器为外伸或更高的矩形附块补充平屋顶，使用该附块自身位置、尺寸及顶部标高。
- 完全被主块包住且不高于主块的附块不增加内部屋顶。宽深小于 2、非矩形附块、显式非平顶附块不使用该自动补齐规则。
- 衍生屋顶保存 host_part_id 与唯一的附块屋顶身份；对齐阶段只有在 part_id 与实际附块位置/标高匹配时保留该位置，不能仅靠模型提供的 marker 绕过主体对齐。
- 保留主屋顶的材质/装饰参数及外挑设置，足迹参数来自附块。屋顶生成仍使用现有 RoofGenerator，不新增生成器。

## 尚未接入

独立 MASS_SECONDARY 与多个 MASS_MAIN 合并为同一建筑、跨 slot 体块转换、任意斜屋顶的交接与排水、附楼单独的立面语法、精确非矩形轮廓以及最终方块覆盖验收仍待实施。nested masses 也不递归展开更深的 masses；没有自动证明体块连通或内部可通行。

下一批优先把外轮廓与屋顶标高用于最终方块覆盖检查，定位缺墙、缺顶和非法覆盖；内部继续保持基础楼板及楼梯。

## 回归

共享样例 `src/test/resources/regressions/multi-mass-geometry.json` 覆盖 L 形附楼、重叠体块和圆形保守报告。Java 验证实际体块生成范围、附楼平屋顶位置、L 形缺口与无效对齐 marker；Python 验证轮廓周长、内部边剔除、附块身份与整栋宽度要求。

本批验证：Java 515 项与后端相关 54 项测试通过，`gradlew build --offline` 成功。后端全套 368 项，仍为相同的 10 项已知失败，输出见 `build/backend-contract-batch4-tests.log`。架构边界检查为 20 项已知债务、0 项新增；`git diff --check` 通过。测试新 jar 时需重启后端。
