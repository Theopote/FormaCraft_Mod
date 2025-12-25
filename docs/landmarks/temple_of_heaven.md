# 天坛·祈年殿（Temple of Heaven / Qiniandian）— Landmark 强原型（v1）

## 1) 如何触发
本地别名命中 `archetype.id = temple_of_heaven`：
- 中文：`天坛` / `祈年殿`
- 英文：`temple of heaven` / `qiniandian`

进入 **LANDMARK_STRONG（强还原）** 条件：
- 本地命中强原型且 `confidence >= 0.85`（当前 Stage-1 固定 0.9）
- 或玩家明确要求：`尽量还原` / `真实比例` / `像` / `强还原`

## 2) 可参数化字段（v1）
你可以在聊天中写：
- **半径/直径（台基）**：`半径 18` / `radius 18` / `直径 36` / `diameter 36`
- **高度（整体）**：`高度 30` / `height=30`
- **细节等级**：`精致/细节更多/refined` -> `detailLevel=refined`（默认 `aesthetic`）
- **台基层数**：默认 **三层**（`三层台基`），也可写 `两层台基`

约束（v1）：
- 台基半径：10 ~ 60
- 总高：18 ~ 80

## 3) 生成器
Java 专用生成器：`TempleOfHeavenGenerator`

v1 标志性轮廓（简化但可识别）：
- 三层圆形台基（每层缩半径）
- 圆殿（柱廊 + 殿身，南向留门洞）
- 三段屋顶（简化为三段圆锥/檐口）

输出轻量 scoring：`shape/ratio/signature/overall`（写入 `GeneratedStructure.description`）

## 4) 示例 Prompt
```text
生成天坛祈年殿，半径 20，高度 34，尽量还原，细节更精致
```


