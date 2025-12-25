# 长城（Great Wall）— Landmark 强原型（v1）

## 1) 如何触发
本地别名命中 `archetype.id = great_wall`：
- 中文：`长城` / `万里长城`
- 英文：`great wall` / `great wall of china`

进入 **LANDMARK_STRONG（强还原）** 条件：
- 本地命中强原型且 `confidence >= 0.85`（当前 Stage-1 固定 0.9）
- 或玩家明确要求：`尽量还原` / `真实比例` / `像` / `强还原`

## 2) 可参数化字段（v1）
聊天中可写：
- **长度**：`长度 200` / `length=200` / `延伸 150`
- **高度**：`高度 12` / `height=12`
- **厚度**：`厚度 5` / `thickness=5`
- **方向**：`朝东/朝西/朝南/朝北`（或英文 `east/west/north/south`）
- **烽火台间距**：`烽火台间距 48` / `塔间距 40` / `tower spacing 64`
- **贴地形**：`贴地形/跟随地形` 或 `不要贴地形/flat`
- **细节等级**：`精致/refined`（否则 `aesthetic`）

约束（v1）：
- 长度：30 ~ 800
- 高度：6 ~ 40
- 厚度：3 ~ 15

## 3) 生成器
Java 专用生成器：`GreatWallGenerator`

v1 轮廓识别点：
- 线性城墙（沿 facing 延伸）
- 可行走顶面（walkway）
- 垛口（crenels）形成“锯齿线”
- 定距烽火台/小城楼（watchtower）
- 可选贴地形（按 WORLD_SURFACE 微调 baseY）

输出轻量 scoring：`shape/ratio/signature/overall`（写入 `GeneratedStructure.description`）

## 4) 示例 Prompt
```text
生成长城，长度 220，高度 12，厚度 5，朝东延伸，烽火台间距 55，贴地形，尽量还原
```


