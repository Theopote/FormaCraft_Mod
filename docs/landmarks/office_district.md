# 办公楼群（Office District）— GRID/CLUSTER 拓扑（v1）

## 1) 如何触发
- 关键词：`办公楼群` / `办公园区` / `写字楼群` / `商业区` / `office district` / `office park`
- Archetype：`office_district`

## 2) v1 结构
由 `OfficeDistrictGenerator` 使用 GRID 骨架生成：
- 网格重复摆放 `office_block`（现代矩形办公楼）
- `office_block` 由 `OfficeBlockGenerator` 生成（平屋顶 + 窗带）

## 3) 可参数化字段（v1）
- 网格：`3x4`（rows×cols）
- 间距：`间距 18` / `spacing 18`
- 道路：默认带简单路网；可写 `不要道路` 关闭
- 道路宽度：`道路宽度 3` / `road width 3`

## 4) 示例 Prompt
```text
生成办公楼群 3x4，间距 18
```


