# 埃菲尔铁塔（Eiffel Tower）— Landmark 强原型（v1）

## 1) 如何触发
以下任意关键词会被本地规则识别为 `archetype.id = eiffel_tower`：
- 中文：`埃菲尔` / `埃菲尔铁塔` / `埃菲尔塔`
- 英文：`eiffel` / `eiffel tower` / `tour eiffel`

并在满足以下任一条件时进入 **LANDMARK_STRONG（强还原）**：
- 本地命中强原型且 `confidence >= 0.85`（当前 Stage-1 固定 0.9）
- 或玩家明确要求：`尽量还原` / `真实比例` / `像` / `强还原`

## 2) 可参数化字段（v1）
你可以在聊天中直接写：
- **高度**：`高度 80` / `height=80`
- **底座宽度**：`底座宽度 27` / `base width=27` / `底边 27`
- **平台数**：v1 默认 2（后续可开放指令参数）
- **细节等级**：`精致/细节更多/refined` -> `detailLevel=refined`（否则 `aesthetic`）

约束（v1）：
- 高度：24 ~ 180
- 底座宽度：15 ~ 81（自动偏好奇数，保证对称）

## 3) 生成器
Java 专用生成器：`EiffelTowerGenerator`
- 标志性轮廓：**四腿收分 + 两层平台 + 顶部尖塔**
- 低成本“像”：按高度插入横撑/斜撑（`iron_bars`）
- v1 输出轻量 scoring：`shape/ratio/signature/overall`（写在 GeneratedStructure.description）

## 4) 示例 Prompt
```text
生成埃菲尔铁塔，高度 90，底座宽度 31，尽量还原，细节更精致
```


