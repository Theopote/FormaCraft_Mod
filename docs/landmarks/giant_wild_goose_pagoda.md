# 大慈恩寺·大雁塔（Giant Wild Goose Pagoda / Dayanta）— Landmark 强原型（v1）

## 1) 如何触发
本地别名命中 `archetype.id = giant_wild_goose_pagoda`：
- 中文：`大慈恩寺` / `大雁塔`
- 英文：`giant wild goose pagoda` / `dayanta` / `wild goose pagoda`

进入 **LANDMARK_STRONG（强还原）** 条件：
- 本地命中强原型且 `confidence >= 0.85`（当前 Stage-1 固定 0.9）
- 或玩家明确要求：`尽量还原` / `真实比例` / `像` / `强还原`

## 2) 可参数化字段（v1）
聊天中可写：
- **层数**：`7层` / `层数 7` / `levels 7`
- **高度**：`高度 50` / `height=50`
- **底座宽度**：`底座宽度 17` / `base width=17`
- **朝向**：`朝南/朝北/朝东/朝西`
- **细节等级**：`精致/refined`（否则 `aesthetic`）

约束（v1）：
- 层数：3 ~ 13
- 总高：18 ~ 120
- 底座宽：9 ~ 41（自动偏好奇数）

## 3) 生成器
Java 专用生成器：`GiantWildGoosePagodaGenerator`

v1 轮廓识别点（简化但可识别）：
- 方形塔身
- 逐层收分（密檐塔意象）
- 每层檐口（slab/trim）
- 顶刹（spike）

输出轻量 scoring：`shape/ratio/signature/overall`（写入 `GeneratedStructure.description`）

## 4) 示例 Prompt
```text
生成大慈恩寺大雁塔，7层，高度 55，底座宽度 19，尽量还原，细节更精致
```


