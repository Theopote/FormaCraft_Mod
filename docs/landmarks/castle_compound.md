# 城堡复合体（Castle Compound）— COMPOUND 拓扑（v1）

## 1) 如何触发
- 关键词：`城堡` / `中世纪城堡` / `要塞` / `堡垒` / `castle` / `fortress` / `keep`
- Archetype：`castle_compound`

## 2) v1 结构
由 `CastleCompoundGenerator` 使用 COMPOUND 组合：
- 矩形围墙（RectEnclosurePlan，带门洞）
- 四角塔楼（复用现有 TowerGenerator）
- 门楼（复用现有 HouseGenerator）

## 3) 可参数化字段（v1）
- 尺寸：`40×30`（宽×深）
- 朝向（门洞边）：`朝南/朝北/朝东/朝西`
- 细节：`精致/refined`
- 铺路：默认开启（城门→内庭/主楼 + 内庭→角楼）；可写 `不要路/不铺路` 关闭
- 路宽：`路宽 3` / `path width 3`

## 4) 示例 Prompt
```text
生成中世纪城堡 64×48，朝南开门，尽量还原
```


