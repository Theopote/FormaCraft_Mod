# 中式院落专用单体首批验收

本批用于验证 courtyard_compound 原生院落中的正房、两侧厢房和贯通门楼。四栋子体使用专用紧凑木构生成器，替代通用房屋；三开间周边柱梁、墙窗与硬山薄屋面是方块化空间表达，不等于明清官式历史复原。民居四合院的 compositional COURTYARD 路由不在本批范围内。

## 提示词

先在平地使用，用户自行打包模组：

> 生成一座一进中式官式院落，围合宽36格、深44格，入口朝南。包含正房、左右厢房和贯通门楼，各房入口朝向院内。设置3格宽的院内路径，保持门楼到正房、院心到厢房的道路通畅。使用原生 courtyard_compound 类型，锚点作为院落底面中心，不要室内前后或左右分隔。

提示词中的尺寸与朝向应出现在计划参数中；自动测试不验证联网模型是否正确路由或理解整段自然语言。如生成了 compositional COURTYARD 或单栋大殿，请提供计划日志。

## 检查项

- 围合范围为36×44格，关于锚点居中。入口朝南，正房位于北侧，两侧厢房朝院内开门。
- 门楼具有两格宽、三格高的前后贯通通道；沿道路能从门楼进入院心，再进入三栋房屋。
- 每栋地板完整，与院内路径在同一标高；四角木柱连续，墙体没有被相邻子体清空。
- 屋脊平行各房入口立面，硬山端墙补全，屋面内部留空；相邻房屋屋檐不相交。
- 将入口改为北、东、西再检查一次。完成平地检查后，在缓坡检查基础补填与整体落地。

日志中的 `[ChineseCourtyard]` 摘要应注明专用木构、硬山薄屋面、围合尺寸、入口及实际 childScale。紧凑布局会缩小派生子体，保持指定围合尺寸；不能容纳四房时明确报错。

## 当前边界

子体默认硬山、一格侧向出檐，无副檐或新增官式斗拱。现在支持完整庭院铺装及各房独立屋型、屋面参数和材料覆盖，参数见下节。尚未实现历史等级比例、多进院、真实廊架及室内功能。`ring_corridor` 仍是路径环路，不是实体回廊。子体 `front_back`/`left_right` 分隔明确拒绝。

## 完整铺装与分房参数

`paveCourtyard=true` 铺满围墙以内地面，不改变共同地面标高；地形补填仍由整体落地处理。默认 false 保留仅铺路径的表达。

`roomDefaults` 为所有子体的默认参数，`rooms` 使用 `main_hall`、`left_wing`、`right_wing`、`gate_house` 四个名称分别覆盖。支持以下字段：

- `roofType`：ying_shan、xuan_shan、xie_shan、wu_dian。
- `roofRise`：3–18格；省略时按各房进深自动推导，不接受显式0。
- `roofOverhang`：0–3格；`roofCurve`：0–100；`roofCornerLift`：0–3格，仅有出檐的歇山/庑殿支持。
- `wall_block`、`roof_block`、`floor_block`、`foundation_block`、`pillar_block`、`beam_block`、`window_block`：有效注册方块ID。空气、流体、未知ID明确拒绝。

优先级为分房参数、roomDefaults、院落共享材料、内置默认。道路不覆盖房内地板。采用最大出檐的保守矩形包络校验，布局会缩小派生子体；无法容纳时明确报错，不扩大指定围合。未知分房名称或不支持字段拒绝，不静默忽略。

计划参数示例（放在原生 STRUCTURE 的 params 中）：

```json
{
  "typology_id": "courtyard_compound",
  "width": 48,
  "depth": 64,
  "facing": "SOUTH",
  "paveCourtyard": true,
  "roomDefaults": {
    "roofOverhang": 2,
    "roofCurve": 40,
    "wall_block": "minecraft:white_concrete",
    "pillar_block": "minecraft:acacia_log",
    "beam_block": "minecraft:birch_log",
    "floor_block": "minecraft:birch_planks",
    "window_block": "minecraft:glass"
  },
  "rooms": {
    "main_hall": {"roofType": "wu_dian", "roofRise": 8, "roofCornerLift": 1},
    "left_wing": {"roofType": "xie_shan", "roofRise": 8, "roofCornerLift": 1},
    "right_wing": {"roofType": "ying_shan", "roofRise": 8},
    "gate_house": {"roofType": "xuan_shan", "roofRise": 8}
  }
}
```

新增能力已写入类型规划提示，但尚未建立针对复杂分房自然语言的后端强制契约；验收需确认实际计划包含相应字段。上述混合屋型案例用于检查生成参数，不是历史官式配置范本。屋面使用所选材料整块表皮；柱梁材料更换不保证真实结构受力。

自动测试检查最终方块、四朝向、奇偶尺寸、三种院落占地、全屋地板、柱位保护、屋顶空腔和院心至四房通行；游戏验收仍待用户反馈。
