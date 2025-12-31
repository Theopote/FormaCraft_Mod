# Meta-Assembly 示例（v1）

本目录提供可以直接复制到 `BuildingSpec.extra.assembly` 的示例，帮助你用“去风格化原子语法”组合出不同文化气质的建筑立面。

## 关键点

- **立面肌理（Surface Pattern）**：由 `SURFACE_PATTERN` op 执行；你通常只需要在组件上写 `facade.surfacePattern`，编译器会自动生成 op。
- **开洞（Openings）**：由 `OPENINGS` op 执行；你通常只需要在组件上写 `facade.openings[]`，编译器会自动生成 op。

两者都支持 `face: "ALL"`（展开为 NORTH/SOUTH/EAST/WEST），或 `"NORTH,EAST"` 这种逗号分隔。

## 示例一：日式（细竖向肋 + 少窗）

文件：`src/main/resources/assets/formacraft/assembly_examples/box_japanese_facade.json`

- 竖向肋条：`pattern=RIBS_V`
- 窗：小、稀疏（`cols` 低，`winW/winH` 小）

## 示例二：现代主义（幕墙网格 + 大窗）

文件：`src/main/resources/assets/formacraft/assembly_examples/box_modern_curtainwall.json`

- 网格：`pattern=GRID`
- 窗：大、密（`cols` 高，`winW/winH` 大）

## 示例三：哥特（竖向条纹 + 高窗）

文件：`src/main/resources/assets/formacraft/assembly_examples/box_gothic_vertical.json`

- 竖向条纹：`pattern=STRIPES_V`
- 窗：尖拱高窗（`kind=ARCH_WINDOW`, `archType=POINTED`）+ 玫瑰窗近似（`kind=ROSE_WINDOW`）

## 示例四：参数化/解构（曲线骨架 + 扫掠管）

文件：`src/main/resources/assets/formacraft/assembly_examples/spline_parametric_tube.json`

- 组件：`type=SPLINE_SWEEP`
- 曲线：`points[]`（控制点）
- 扫掠：沿样条采样后用“体素管”铺设
- taper：用 `r0/r1` 做半径渐变（收分）

## 示例五：参数化/解构（曲线骨架 + 扭转带）

文件：`src/main/resources/assets/formacraft/assembly_examples/spline_twisted_ribbon.json`

- 组件：`type=SPLINE_SWEEP`
- 截面：`profile=RECT`（`profileW/profileH`）
- 扭转：`twistTurns`（沿整条曲线旋转的圈数）

## 示例六：参数化/解构（曲线骨架 + 扭转 + 收分）

文件：`src/main/resources/assets/formacraft/assembly_examples/spline_twisted_tapered_ribbon.json`

- taper：`profileW0/profileW1/profileH0/profileH1`（RECT 截面沿路径插值）

## 示例七：参数化/解构（曲线壳体：hollow + 端盖）

文件：`src/main/resources/assets/formacraft/assembly_examples/spline_hollow_shell_ribbon.json`

- `hollow=true` + `thickness`
- `capEnds=true` + `capThickness`：端部封口（更像“壳体”而不是“框线”）
- 可选 `carveInterior=true`：沿扫掠路径把截面内部挖空（谨慎使用，会破坏已有方块）

## 示例八：参数化/解构（自由截面扫掠：profile=POLYGON）

文件：`src/main/resources/assets/formacraft/assembly_examples/spline_polygon_profile.json`

- `profile=POLYGON` + `profilePoints`（截面 2D 点）
- 可用 `profileScale0/profileScale1` 做截面缩放渐变

## 曲线扫掠通用参数补充（推荐）

- **`profileFrame` / `frame`**：截面所在的参考平面
  - `PATH`（默认）：截面随路径切线自适应（更“流体/解构”）
  - `WORLD_XY` / `WORLD_XZ` / `WORLD_YZ`：锁定到世界平面（更“工程/规整”）
- **`profileSnap` / `snap`**：截面偏移坐标取整方式（影响体素锯齿与偏移）
  - `ROUND`（默认）
  - `FLOOR`
  - `CEIL`

- **`connectSamples`**：是否在相邻采样截面之间做轻量级“连通补洞”（减少曲线壳体偶发断点）
  - `false`（默认）
  - `true`：对同一个截面网格点（RECT 的 uu/vv；POLYGON 的 u/v）在相邻采样截面之间做短距离插值连接
- **`connectMaxStep`**：连通的最大允许间距（越大越容易连上，但也更可能产生“拉丝”）
  - 推荐：`2`
- 可用 `profileFrame` 控制截面坐标系（`PATH` 随曲线旋转；`WORLD_XY/WORLD_XZ/WORLD_YZ` 世界对齐）
- 可用 `profileSnap` 控制体素化取整（`ROUND/FLOOR/CEIL`）

## 示例七：参数化/解构（曲线骨架 + 空心走廊壳）

文件：`src/main/resources/assets/formacraft/assembly_examples/spline_hollow_corridor.json`

- 截面：`profile=RECT`
- 空心壳：`hollow=true` + `thickness`
- 端面封口：`capEnds=true`（默认也是 true）

## `facade.surfacePattern` 字段（组件侧）

```json
{
  "surfacePattern": {
    "face": "ALL",
    "pattern": "GRID",
    "step": 3,
    "thickness": 1,
    "material": "minecraft:stone_brick_wall"
  }
}
```

支持的 `pattern`（当前实现）：
- `GRID`
- `STRIPES_V` / `STRIPES_H`
- `RIBS_V` / `RIBS_H`

## `facade.openings[]` 字段（组件侧）

窗口网格：

```json
{
  "openings": [
    {
      "face": "ALL",
      "kind": "WINDOW_GRID",
      "rows": 2,
      "cols": 4,
      "winW": 2,
      "winH": 3,
      "sillY": 3,
      "marginX": 2,
      "marginY": 2,
      "gapX": 2,
      "gapY": 2,
      "frameThickness": 1,
      "mullionStep": 0,
      "fill": "minecraft:glass_pane",
      "frame": "minecraft:smooth_stone"
    }
  ]
}
```

门（最小实现）：

```json
{
  "openings": [
    {
      "face": "SOUTH",
      "kind": "DOOR",
      "doorW": 2,
      "doorH": 3,
      "frameThickness": 1,
      "frame": "minecraft:smooth_stone"
    }
  ]
}
```

尖拱/圆拱窗（最小实现，按网格批量布置）：

```json
{
  "openings": [
    {
      "face": "NORTH",
      "kind": "ARCH_WINDOW",
      "archType": "POINTED",
      "archThickness": 2,
      "keystone": "minecraft:polished_blackstone",
      "keystoneOn": true,
      "tracery": "CROSS",
      "traceryThickness": 1,
      "traceryMaterial": "minecraft:iron_bars",
      "traceryInset": 1,
      "foilRadius": 3,
      "foilCenterY": "AUTO",
      "foilCount": "AUTO",
      "foilStepY": "AUTO",
      "rows": 2,
      "cols": 4,
      "winW": 2,
      "winH": 6,
      "sillY": 6,
      "frameThickness": 1,
      "fill": "minecraft:purple_stained_glass_pane",
      "frame": "minecraft:deepslate_bricks"
    }
  ]
}
```

玫瑰窗近似（单个居中放置）：

```json
{
  "openings": [
    {
      "face": "SOUTH",
      "kind": "ROSE_WINDOW",
      "r": 5,
      "ring": 1,
      "petals": 12,
      "phase": 0.08,
      "spokeWidth": 2,
      "spokeThreshold": 0.05,
      "innerFill": "minecraft:black_stained_glass_pane",
      "spokeMaterial": "minecraft:iron_bars",
      "centerY": 18,
      "fill": "minecraft:purple_stained_glass_pane",
      "frame": "minecraft:deepslate_bricks"
    }
  ]
}
```


