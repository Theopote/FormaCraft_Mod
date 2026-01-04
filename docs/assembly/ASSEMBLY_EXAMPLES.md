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

### 更强的表面语法（P0）：`FACADE_GRID` 与 `SURFACE_BANDS`

在 `SHELL_BOX` 组件上，你可以用更高阶的 `facade.*` 字段表达“幕墙模数 / 檐口腰线 / 柱网”，编译器会自动展开成对应 op：

- **`facade.facadeGrid`** -> `FACADE_GRID`：模数化幕墙（竖向 mullion + 横向 transom + 面板填充）
- **`facade.surfaceBands`** -> `SURFACE_BANDS`：檐口/腰线（水平带）+ 柱网/肋（竖向带）

补充（P1）：

- **`FACADE_GRID.spandrel*`**：楼层带/窗间墙（把每层某一条横向带改成实墙/板材）
- **`SURFACE_BANDS.*.outset`**：外挑压线/檐口（让带状构件更“立体”）

## 示例十一：现代主义（更强幕墙：FACADE_GRID）

文件：`src/main/resources/assets/formacraft/assembly_examples/box_modern_facade_grid.json`

- `facade.facadeGrid`：直接生成“框 + 玻璃面板”的幕墙模数，而不是只画线

## 示例十三：现代主义（幕墙楼层带：FACADE_GRID + spandrel）

文件：`src/main/resources/assets/formacraft/assembly_examples/box_modern_facade_grid_spandrel.json`

- `spandrelEvery/spandrelHeight/spandrelOffset`：定义“每层”的窗间墙带位置与厚度
- `spandrelFill`：楼层带填充材质（例如浅灰混凝土）

## 宏参数表（P0）：`assembly.macro`（高层滑块 -> 低层原子语法）

当你希望 LLM 用更接近“基因表”的方式输出，而不是直接写大量细节字段，可以使用 `macro`。
执行管线是：**normalize → apply macro → validate → compile/execute**，并且遵循 **显式参数优先**（macro 只在缺省时注入/改写）。

当前支持的宏字段（P0）：
- `primaryComponent`：宏作用的主组件 id（默认取第一个组件）
- `shapeType`：`RECTANGLE` / `CIRCLE`（其余会给 warning）
- `heightScale`：数值缩放（或 `LOW/MEDIUM/HIGH`）
- `openness`：0..1（会调整 `facade.openings` 的 rows/cols 密度）
- `symmetry`：会映射到连接的 `routingStyle`（AXIAL/RADIAL/SYMM -> PLANNED；ASYMM -> ORGANIC）
- `roofType`：`FLAT/GABLE`（若当前没有屋顶组件，会自动添加一个 `ROOF_COVER`）
- `overhang`：`NONE/SMALL/MEDIUM/LARGE`（影响自动添加屋顶的 overhang）
- `roofCurvature`：0..1 或 `LOW/MEDIUM/HIGH`（驱动 `ROOF_COVER.rise`；不覆盖显式 rise）
- `bridgeTower`：桥塔一键注入（会向 `graph.components` 添加：`SHELL_BOX` 塔体 + `ANCHOR_FOOTPRINT` 深基础 + 顶部 `CYLINDER` 索鞍滚轮）
  - 并会在桥塔组件上补一组 `ports` 语义端口别名，便于连接缆索/道路：`Tower.saddle_left/right/saddle_center`、`Tower.cable_top` 等
  - 可选：`notch` / `notch*` + `holes`：在塔顶 **carve 索鞍槽 + 穿索孔**（宏会注入 `CLEAR_BOX` 雕刻组件）
- `style` / `culture`：文化/风格宏（**上层滑块 → 原子组合注入**）
  - 输入：`styleId + intent + density/symmetry/verticality/transparency/structureExposure`
  - 输出（P0 覆盖）：会自动注入 `paletteId`、`facade.openings/surfaceBands`、以及结构原子（例如 `BUTTRESS` 或 `FRAME_GRID_3D`）

## 示例十四：宏参数（现代塔楼）

文件：`src/main/resources/assets/formacraft/assembly_examples/macro_modern_tower.json`

- 只给出一个基础 `SHELL_BOX`，其余由 `macro` 注入：`heightScale`、`roofType/overhang`、`openness`、`symmetry`

## 示例十五：宏参数（六边亭：HEXAGON + roofCurvature）
- 另见：`src/main/resources/assets/formacraft/assembly_examples/macro_bridge_tower.json`（bridgeTower 一键注入桥塔）
- 另见：`src/main/resources/assets/formacraft/assembly_examples/macro_style_gothic_box.json`（macro.style：哥特）
- 另见：`src/main/resources/assets/formacraft/assembly_examples/macro_style_industrial_exoskeleton.json`（macro.culture：工业外骨骼）

文件：`src/main/resources/assets/formacraft/assembly_examples/macro_hex_pavilion.json`

- `shapeType=HEXAGON`：把主组件转换为 `EXTRUDE_POLYGON` 六边形
- `roofCurvature`：会设置 `ROOF_COVER.rise`（更“陡”的屋顶）

## 示例十六：结构骨架（TRUSS_2D 桁架）

文件：`src/main/resources/assets/formacraft/assembly_examples/truss_simple_bridge.json`

- 组件：`type=TRUSS_2D`
- 走向：由 `from/to` 定义（XZ 平面），高度：`height`
- 模块节奏：`module`（每隔 N 步放一个节点）
- 模式：推荐 `pattern=WARREN`（P0 其余模式会按 WARREN 近似）

## 示例十七：结构骨架（ARCH_RIB 拱肋）

文件：`src/main/resources/assets/formacraft/assembly_examples/arch_rib_bridge.json`

- 组件：`type=ARCH_RIB`
- 端点：`from/to`（包含 y 高度）
- 矢高：`rise`（在端点连线的基础上向上抬拱）
- `samples` 可选：采样点数（不填会按跨度自动估算）

## 示例十八：哥特（飞扶壁：BUTTRESS）

文件：`src/main/resources/assets/formacraft/assembly_examples/gothic_flying_buttress.json`

- 组件：`type=BUTTRESS`
- `from`：主墙上部连接点；`to`：外侧扶壁墩连接点
- `rise`：飞拱弧度（矢高）；`pierDown`：扶壁墩向下“插入”高度（纯几何，不做地形探测）

## 示例十九：工业（斜拉桥简化：TENSION_CABLE）

文件：`src/main/resources/assets/formacraft/assembly_examples/cable_stayed_bridge_simplified.json`

- 组件：`type=TENSION_CABLE`
- `sag`：下垂量（P0 用抛物线近似悬链线；`sag` 越大越下垂）
- `material`：建议用语义 `STRUCTURAL_CABLE`（默认会回退到 `STRUCTURAL_BEAM/chain`）

## 示例二十：工业（悬索桥简化：主缆 + 吊索：TENSION_CABLE + hangers*）

文件：`src/main/resources/assets/formacraft/assembly_examples/suspension_bridge_hangers_simplified.json`

- `hangersEvery`：每隔 N 个采样点放一根垂直吊索（越小越密）
- `hangersToY`：吊索下端目标高度（P0 固定 Y，不做地形探测）
- `hangersMaterial`：吊索材质（默认继承主缆）

## 示例二十一：工业（悬索桥索面：多主缆 + 吊索：TENSION_CABLE + cable*）

文件：`src/main/resources/assets/formacraft/assembly_examples/suspension_bridge_cable_plane.json`

- `cableCount`：主缆根数（并行复制）
- `cableSpacing`：并行主缆间距
- `cableAxis`：并行偏移方向 `AUTO/X/Z`

## 示例二十二：工业桥梁终局（桥塔锚固 + 主缆锚碇块：ANCHOR_FOOTPRINT + ANCHORAGE）

文件：`src/main/resources/assets/formacraft/assembly_examples/industrial_bridge_anchorage_endgame.json`

- `ANCHOR_FOOTPRINT`：桥塔深基础（会用 world 探测向下填充到实心地面或到 `maxDepth`）
- `ANCHORAGE`：主缆锚碇块（大体量实体块 + 深基础 + 细节化）
  - `topBevel`：顶面台阶式倒角/坡面
  - `holes[]`：从某个面向内挖“拉索孔道”（air tunnel）
  - `guardWallHeight/guardWall*`：顶部护墙/女儿墙（可选 `guardWallCrenels` 做齿口节奏）
- `Backstay*`：从塔顶回拉到锚碇块（工业桥常见“回拉索”视觉）

## 示例二十三：结构骨架（空间外骨骼：FRAME_GRID_3D）

文件：`src/main/resources/assets/formacraft/assembly_examples/frame_grid_3d_exoskeleton.json`

- `mode=SURFACE`：只生成外骨骼（边界面网格）
- `mode=ALL`：生成完整 3D 空间格架（体内也有网格）
- `diagonal=FACE`：在外表面网格单元上自动加斜撑（交错方向）
- `diagonal=SPACE`：在 `mode=ALL` 时可加“体对角撑”（空间桁架风格）

## 示例二十四：楼梯系统（STAIR_SYSTEM）

文件：`src/main/resources/assets/formacraft/assembly_examples/stair_system_basic.json`

- `from/to`：楼梯起止点
- `width`：楼梯宽度
- `clearHeight + carve`：自动挖出净空（避免撞头）

## 示例二十五：曲面壳体（BEZIER_SURFACE：贝塞尔曲面片）

文件：`src/main/resources/assets/formacraft/assembly_examples/bezier_surface_patch.json`

- `BEZIER_SURFACE.points`：**16 个控制点**（支持 4x4 网格写法）
- `uSamples/vSamples`：曲面采样密度（越大越平滑，但方块量更高）
- `connectSamples=true`：自动把相邻采样点用短梁连接，减少曲面“断点/漏风”

## 示例二十六：连续面系统（REVOLVE_SURFACE：旋转面）

文件：`src/main/resources/assets/formacraft/assembly_examples/revolve_surface_vase.json`

- 2D 轮廓线 `profilePoints` 写在 (r,y) 平面（`x=r`，`y=y`），绕 Y 轴旋转生成壳体
- `segments` 控制圆周采样密度；`angleDeg` 可做非 360° 的开口旋转

## 示例二十七：连续面系统（LOFT_SURFACE：多截面放样）

文件：`src/main/resources/assets/formacraft/assembly_examples/loft_surface_ribbon.json`

- `sections[]`：每段提供 `at`（空间位置）+ `profilePoints`（2D 截面）
- P0 约束：各段 `profilePoints` 点数需要一致（便于稳定训练/生成）

## 示例二十八：多 patch 拼接（BEZIER_SURFACE_SET：共享边自动补缝 + 网格拓扑）

文件：`src/main/resources/assets/formacraft/assembly_examples/bezier_surface_set_grid.json`

- `patches[]`：每个 patch 一张 4x4 贝塞尔曲面片（`points`=16 控制点）
- **共享边自动补缝**：当两张 patch 的边界采样点完全一致时，会自动 `stitch`（跨 patch 连边补缝）
- `topology.grid`：可显式声明网格邻接（`["A","B"]` 相邻会按右/下方向强制补缝），同时兼容旧字段 `grid`

## 示例二十九：多 patch 拼接（容差拼缝：stitchEpsilon + RESAMPLE）

文件：`src/main/resources/assets/formacraft/assembly_examples/bezier_surface_set_epsilon.json`

- 当两张 patch 的共享边**不完全一致**时，可用：
  - `stitchEpsilon`：允许的误差半径（单位：方块）
  - `stitchSamples`：拼缝采样点数（越高越稳但更慢）
  - `stitchResampleMode=RESAMPLE`：对两条边做重采样对齐后再补缝

## 示例三十：多 patch 拼接（显式拓扑：topology.links）

文件：`src/main/resources/assets/formacraft/assembly_examples/bezier_surface_set_links.json`

- `topology.links[]`：用 “边-边连接” 明确指定拼缝关系（适合非规则拓扑）
  - `a/b`：patch id（也兼容 `from/to`）
  - `ea/eb`：边名（支持 `U0/U1/V0/V1`，也支持 `LEFT/RIGHT/TOP/BOTTOM`）
  - `epsilon/samples/resampleMode/thickness`：每条 link 的独立拼缝参数（覆盖 set-level 默认）

## 示例三十一：多 patch 拼接（T-junction：子边段 aRange/bRange）

文件：`src/main/resources/assets/formacraft/assembly_examples/bezier_surface_set_tjunction.json`

- `aRange/bRange`：在 0..1 参数域里指定“只拼接边的一段”（用于 T-junction/分叉）
  - 例：`aRange:[0.35,0.65]` 表示只取主边中间 30% 的子段与另一条边拼接

## 示例三十二：多 patch 拼接（拼缝带/盖板：capWidth + capMaterial）

文件：`src/main/resources/assets/formacraft/assembly_examples/bezier_surface_set_seam_cap.json`

- `capWidth/capMaterial`：沿拼缝额外生成一条“遮缝带”（优先用于消除体素化的小漏缝）
  - 可在 **op 级**设置默认值，也可在 `topology.links[]` **link 级覆盖**

## 示例三十三：厚壳偏移（SURFACE_OFFSET）

文件：`src/main/resources/assets/formacraft/assembly_examples/surface_offset_bezier_shell.json`

- `source.kind=BEZIER_SURFACE`：从贝塞尔曲面采样网格估计法线
- `offset`：沿法线偏移距离（格）
- `shellThickness`：沿法线方向生成厚度
- `mode=BOTH/OUT/IN`：双向/外向/内向
- `normalMode=DDA/AXIS`：法线离散策略（DDA 更圆润但更贵；AXIS 更快更硬朗）
- `stepLen`：DDA 每步的步长（<1 更细腻但更贵；默认 1）
- `dedupe`：DDA 去重（避免多步 round 到同一格重复写入；默认 true）
- `connectSamples/connectMaxStep`：在相邻步落点之间做短距离补线，减少孔洞（默认 false/2）

## 示例三十四：隐式曲面（IMPLICIT_FIELD：体素等值面）

文件：`src/main/resources/assets/formacraft/assembly_examples/implicit_field_sphere.json`

- `kind=SPHERE/TORUS/METABALLS`
- `iso + band`：以 `iso` 为等值面阈值，在 `band` 带宽内并且发生邻域符号变化的体素会被保留为“壳层”

## 示例三十五：隐式曲面（MARCHING_CUBES：marching tetrahedra + 三角片体素化）

文件：`src/main/resources/assets/formacraft/assembly_examples/marching_cubes_torus.json`

- P0 实现使用 marching tetrahedra（避免大表），再对三角片做重心采样体素化
- `fill`：三角片填充密度（越大越平滑但更慢）
- `support`：简单支撑（P0：每步下方补一层承重块）

## 示例十二：古典（檐口/腰线/柱网：SURFACE_BANDS）

文件：`src/main/resources/assets/formacraft/assembly_examples/box_classical_bands_columns.json`

- `horizontalBands[]`：底部线脚 + 腰线 + 顶部檐口/压顶
- `verticalBands[]`：按步距生成柱网（立面节奏更“古典”）

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

## 示例九：参数化/解构（多环/带洞截面：profileRings）

文件：`src/main/resources/assets/formacraft/assembly_examples/spline_polygon_hole_profile.json`

- `profileRings[0]`：外环
- `profileRings[1..]`：洞（会从外环里扣掉）

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

## SPLINE_SWEEP 端口（Ports）与“切线方向自动对接”

编译器会为 `SPLINE_SWEEP` 组件自动生成端口：

- **位置端口（已有）**：
  - `start` / `end`
  - 同义词：`entrance/in`（= start）、`exit/out`（= end）
- **切线方向端口（可连接，用于自动对接道路/桥/廊道）**：
  - `start_north|start_south|start_east|start_west`
  - `end_north|end_south|end_east|end_west`

方向由样条的 **首段/末段切线** 估算：`start_*` 使用 `points[1]-points[0]`，`end_*` 使用 `points[last]-points[last-1]`（取 XZ 主导轴）。

并且有一个 **自动重写** 规则：

- 当连接端点引用的是 `start/end/entrance/exit/in/out`（无方向），且该组件是 `SPLINE_SWEEP`，编译器会自动重写成对应的 `start_*` / `end_*`，
  以便 A* 的 `routingLeadOut/routingLeadIn`、`routingPreferDoorAxis` 等逻辑能推断更合理的轴向/朝向。

最常用写法示意（你可以只写 `start/end`，不用手动写方向端口）：

```json
{
  "connections": [
    { "type": "PATH", "from": "Tube.start", "to": "Hall.entrance", "routing": "ASTAR", "routingAutoLead": true }
  ]
}
```

#### `routingLeadHard`（推荐用于 spline 对接）

- **含义**：强制把 lead-out/lead-in 变成“硬对接”——在端口方向上插入显式的 via（相当于出入口的一小段直线 landing），而不是只依赖 A* 的软约束代价。
- **默认值**：当 `routingAutoLead=true` 且端口是 `start_* / end_*` 这类方向端口时，默认视为 `true`（更稳定的对接效果）。
- **手动覆盖**：你可以显式设置 `routingLeadHard: false` 来恢复纯软约束。

## 示例七：参数化/解构（曲线骨架 + 空心走廊壳）

文件：`src/main/resources/assets/formacraft/assembly_examples/spline_hollow_corridor.json`

- 截面：`profile=RECT`
- 空心壳：`hollow=true` + `thickness`
- 端面封口：`capEnds=true`（默认也是 true）

## 示例十：Spline 连接（只写 start/end，自动使用切线方向端口对接 PATH）

文件：`src/main/resources/assets/formacraft/assembly_examples/spline_connect_path_auto_tangent_ports.json`

- `from: "Tube.end"`：这里没有写 `end_east/end_north`，编译器会自动重写到 `end_*`（基于末段切线）
- `routingAutoLead=true`：A* 会利用该方向信息，更自然地“引出/引入”道路

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


