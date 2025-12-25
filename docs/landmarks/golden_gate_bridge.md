# 金门大桥（Golden Gate Bridge）— Landmark 强原型（v1）

## 1) 如何触发
本地别名命中 `archetype.id = golden_gate_bridge`：
- 中文：`金门大桥`
- 英文：`golden gate bridge` / `golden gate`

进入 **LANDMARK_STRONG（强还原）** 条件：
- 本地命中强原型且 `confidence >= 0.85`（当前 Stage-1 固定 0.9）
- 或玩家明确要求：`尽量还原` / `真实比例` / `像` / `强还原`

## 2) 可参数化字段（v1）
聊天中可写：
- **跨度**：`跨度 220` / `span 180` / `主跨 200`
- **桥面宽度**：`桥面宽度 9` / `deck width 11`
- **塔高**：`塔高 46` / `tower height 50`
- **方向**：`朝东/朝西/朝南/朝北`
- **贴地形**：`贴地形/跟随地形` 或 `不要贴地形/flat`
- **细节等级**：`精致/refined`（否则 `aesthetic`）

约束（v1）：
- 跨度：60 ~ 800
- 桥面宽度：5 ~ 41（自动偏好奇数）
- 塔高：18 ~ 120

## 3) 生成器
Java 专用生成器：`GoldenGateBridgeGenerator`

v1 轮廓识别点（简化但可识别）：
- 双主塔（红色）
- 桥面直线 + 护栏
- 两侧主缆（抛物线近似）
- 吊索（竖向 hangers，塔间区域每 2 格一根）

输出轻量 scoring：`shape/ratio/signature/overall`（写入 `GeneratedStructure.description`）

## 4) 示例 Prompt
```text
生成金门大桥，跨度 240，桥面宽度 11，塔高 52，朝东延伸，贴地形，尽量还原
```


