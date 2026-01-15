# Skeleton × Socket × Component（自动装配系统）实现总结

## 📋 实现内容

根据建议，已成功实现 Skeleton × Socket × Component 自动装配系统，让"建筑骨架（Skeleton）"产生 Socket，并定义"哪些构件族（Archetype/Component）可以长在哪些 Socket 上"，从而实现 AI 自动装配与风格一致的细节生成。

## 🎯 核心目标

**核心思想**：
- ✅ 让"建筑骨架（Skeleton）"产生 Socket
- ✅ 定义"哪些构件族（Archetype/Component）可以长在哪些 Socket 上"
- ✅ 实现 AI 自动装配与风格一致的细节生成
- ✅ 可插拔、数据驱动、AI-first 的"自动装配规则系统"

**完整链路**：
```
LLM 输出：Skeleton + Program(components intent) + StyleProfile + TerrainStrategy
   ↓
Skeleton → Geometry(墙/屋顶/边缘/洞口...)  ← 你已有/正在做
   ↓
Geometry → SocketProvider（按骨架生成 sockets）
   ↓
ComponentQuery（由 Program / Style / Context 生成）
   ↓
ComponentRanker（从库挑原型）
   ↓
VariantGenerator（生成变体）
   ↓
SocketMatcher（找可用 socket）
   ↓
Patch（diff）→ Preview → Apply
```

## ✅ 已实现的组件

### 1. SocketDensity（Socket 密度）

**位置**：`src/main/java/com/formacraft/common/skeleton/socket/SocketDensity.java`

**核心功能**：
- 定义每种 socket 的默认"密度/生成参数"
- 简单但好用的 v1

**关键字段**：
- `density` - 生成密度（0~1），用于沿墙/边缘布点
- `openW / openH` - opening 的建议尺寸（方块）

**关键方法**：
- `openings(double density, int w, int h)` - 创建开口密度（用于 WALL_OPENING）
- `sparse(double density)` - 创建稀疏密度（用于装饰性 socket）

### 2. SkeletonSocketProfile（骨架的 Socket 供给能力）

**位置**：`src/main/java/com/formacraft/common/skeleton/socket/SkeletonSocketProfile.java`

**核心功能**：
- Skeleton 不同类型，会产出不同 Socket 组合与密度
- 定义骨架的"Socket 供给能力"

**示例**：
- **城墙**：EDGE_OUTER / WALL_SURFACE / WALL_OPENING（高窗/射孔）
- **塔楼**：WALL_SURFACE / ROOF_RIDGE / EDGE_OUTER / WALL_OPENING（小窗）
- **道路**：FLOOR_SURFACE / EDGE_OUTER（路灯、栏杆）
- **屋顶**：ROOF_SLOPE / ROOF_RIDGE（老虎窗、脊兽、烟囱）

**关键字段**：
- `skeletonType` - 骨架类型
- `providedSocketTypes` - 提供的 Socket 类型集合
- `density` - 每种 socket 的默认"密度/生成参数"

**关键方法**：
- `forCastleWall()` - 创建城墙的 Socket Profile
- `forTower()` - 创建塔楼的 Socket Profile
- `forRoad()` - 创建道路的 Socket Profile
- `forRoof()` - 创建屋顶的 Socket Profile

### 3. SkeletonComponentRules（骨架上的"构件长出来规则"）

**位置**：`src/main/java/com/formacraft/common/assembly/SkeletonComponentRules.java`

**核心功能**：
- 这层解决"城墙上默认长什么""塔楼上默认长什么""道路边放什么"
- 这不是 AI 选构件，而是给 AI 一个"默认装配语境"
- 让 LLM 能更稳定地产生结构化 Program，并且系统也能在缺省情况下补齐细节

**Rule 类字段**：
- `socketType` - Socket 类型
- `role` - 构件角色（door/window/railing/ornament）
- `tags` - 语义标签
- `weight` - 在多规则下抽样权重
- `required` - 是否必须
- `minCount / maxCount` - 数量范围

**关键方法**：
- `defaultMedieval()` - 提供默认规则集（中世纪风格）

**默认规则示例**：
- **城墙**：外边缘栏杆/垛口 + 墙面射孔/小窗
- **塔楼**：小窗 + 顶部装饰（旗帜/尖顶等）
- **道路**：边缘路灯（可选）

### 4. AssemblyPlanner（装配规划器）

**位置**：`src/main/java/com/formacraft/common/assembly/AssemblyPlanner.java`

**核心功能**：
- 把规则翻译成 ComponentQuery（给 Ranker 用）
- 多数情况下 AI 会自动选构件
- 但当 LLM 没说清时，系统可用规则补齐
- 或反过来：LLM 输出 Program，系统用规则做"纠偏"

**关键方法**：
- `toQueries(rules, socketType, styleProfile, materialTone)` - 将规则转换为 ComponentQuery 列表

**转换逻辑**：
- 从 Rule 提取 role, tags, weight, required
- 根据 socketType 推导 placement, side, heightLevel, edgeCondition
- 设置 geometry（requiresOpening, scalable, tolerance）
- 设置 style（styleProfile, materialTone）
- 设置 constraints（mustHave, forbiddenTags）
- 设置 usage（frequency, visibility）

### 5. AutoAssembler（自动装配器）

**位置**：`src/main/java/com/formacraft/common/assembly/AutoAssembler.java`

**核心功能**：
- Socket × Query × Rank × Variant × Place
- 把我们前面所有模块串起来

**完整链路**：
- Socket → Query（AssemblyPlanner）
- Query → Rank（ComponentRetriever）
- Rank → Variant（VariantGenerator）
- Variant → Match（SocketMatcher）
- Match → Place（ComponentPlanCompiler）

**关键方法**：
- `assembleOnSockets(sockets, rules, skeletonKind, styleProfile, materialTone, random)` - 在 Socket 上自动装配构件

**返回结果**：
- `AssemblyResult` - 包含 ComponentDefinition + ComponentVariant + Socket

## 📊 使用示例

### 示例 1：城墙自动长窗 + 栏杆

```java
// 输入
String skeletonKind = "WALL";
List<Socket> sockets = WallSocketProvider.provideSockets(ctx);
String styleProfile = "MEDIEVAL_CLASSIC";
String materialTone = "dark_stone";

// 规则
SkeletonComponentRules rules = SkeletonComponentRules.defaultMedieval();

// 自动装配
List<AssemblyResult> results = AutoAssembler.assembleOnSockets(
    sockets, rules, skeletonKind, styleProfile, materialTone, random
);

// 输出（效果）
// EDGE_OUTER 上自动选出 railing/battlement 族构件并沿边重复
// WALL_OPENING 上自动选 arrow_slit 或 gothic window（按 Rank）
// 局部缺口处自动跳过（SocketMatcher / PatchFilter）
```

### 示例 2：路径工具集成

```java
// Path skeletonKind = "ROAD" 或 "WALL"（长城沿路）
PathSkeleton path = PathTool.getSkeleton();

// SocketProvider for Path：
// - FLOOR_SURFACE（路面）
// - EDGE_OUTER（路边）
// - WALL_SURFACE / WALL_OPENING（长城墙体段）

// 规则：
// - ROAD + EDGE_OUTER → lamp / fence / tree_planter
// - WALL + EDGE_OUTER → battlement
// - WALL + WALL_OPENING → arrow_slit

// 路径工具会变成"骨架输入"，无需额外特判
```

### 示例 3：塔楼自动装配

```java
// 输入
String skeletonKind = "TOWER";
SkeletonSocketProfile profile = SkeletonSocketProfile.forTower();
List<Socket> sockets = generateSocketsFromProfile(profile);

// 规则
SkeletonComponentRules rules = SkeletonComponentRules.defaultMedieval();

// 自动装配
List<AssemblyResult> results = AutoAssembler.assembleOnSockets(
    sockets, rules, skeletonKind, "GOTHIC_MEDIEVAL", "dark_stone", random
);

// 输出（效果）
// WALL_OPENING 上自动选小窗（gothic, small）
// ROOF_RIDGE 上自动选装饰（flag, medieval）
```

## 🔄 完整数据流

```
[ 建筑骨架（Skeleton）]
        ↓
[ SkeletonSocketProfile：定义 Socket 供给能力 ]
        ↓
[ SocketProvider：生成 Socket 列表 ]
        ↓
[ SkeletonComponentRules：定义构件长出来规则 ]
        ↓
[ AssemblyPlanner：规则 → ComponentQuery ]
        ↓
[ ComponentRetriever：Query → Rank（排序）]
        ↓
[ VariantGenerator：生成变体 ]
        ↓
[ SocketMatcher：匹配 Socket ]
        ↓
[ ComponentPlanCompiler：编译为 Patch ]
        ↓
[ 应用到世界 ]
```

## 🎯 核心能力

### ✅ 1. 可插拔

- SkeletonSocketProfile 可以扩展（forCastleWall, forTower, forRoad, forRoof）
- SkeletonComponentRules 可以扩展（defaultMedieval, defaultModern 等）
- 全部是数据驱动

### ✅ 2. 数据驱动

- 没有"特判"
- 全部是规则配置
- 易于扩展和维护

### ✅ 3. AI-first

- 多数情况下 AI 会自动选构件
- 但当 LLM 没说清时，系统可用规则补齐
- 或反过来：LLM 输出 Program，系统用规则做"纠偏"

### ✅ 4. 路径工具集成

- Path skeletonKind = "ROAD" 或 "WALL"（长城沿路）
- SocketProvider for Path 自动生成 Socket
- 规则自动应用
- 路径工具会变成"骨架输入"，无需额外特判

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| SocketDensity | ✅ 完成 | Socket 密度/生成参数 |
| SkeletonSocketProfile | ✅ 完成 | 骨架的 Socket 供给能力 |
| SkeletonComponentRules | ✅ 完成 | 构件长出来规则 |
| AssemblyPlanner | ✅ 完成 | 规则翻译成 ComponentQuery |
| AutoAssembler | ✅ 完成 | 装配执行器（串起所有模块） |

## 🎉 总结

已成功实现 Skeleton × Socket × Component 自动装配系统：

- ✅ **SocketDensity** - Socket 密度/生成参数
- ✅ **SkeletonSocketProfile** - 骨架的 Socket 供给能力
- ✅ **SkeletonComponentRules** - 构件长出来规则
- ✅ **AssemblyPlanner** - 规则翻译成 ComponentQuery
- ✅ **AutoAssembler** - 装配执行器（串起所有模块）

**这一层完成后，系统立刻获得的能力**：
- ✅ 让"建筑骨架（Skeleton）"产生 Socket
- ✅ 定义"哪些构件族（Archetype/Component）可以长在哪些 Socket 上"
- ✅ 实现 AI 自动装配与风格一致的细节生成
- ✅ 可插拔、数据驱动、AI-first 的"自动装配规则系统"
- ✅ 路径工具会变成"骨架输入"，无需额外特判

**这就是你要求的"用户不知道用了哪个构件，只看到成品很像一套。"**

---

**实现时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，已集成到自动装配系统
