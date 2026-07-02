# 修复缺失组件生成器问题

## 问题描述

用户反映：不管输入什么建造要求，最后建出来的建筑长得一模一样，完全与描述不符。

## 问题根源

从日志分析发现：

1. **LLM 返回了正确的组件类型**：
   - 第一个请求：`WALL_SEGMENT`（长城）
   - 第二个请求：`MASS_MAIN`, `ENTRANCE`, `MASS_SECONDARY`, `CONNECTOR`（扎哈风格建筑）

2. **但是 ComponentGeneratorRegistry 中缺少注册**：
   - `WALL_SEGMENT` 未注册
   - `CONNECTOR` 未注册

3. **回退机制导致问题**：
   - `SmartGeneratorRouter` 找不到生成器
   - 回退到传统系统（`StructureGeneratorAdaptor`）
   - 传统系统看到 `CUSTOM` 类型
   - 最终回退到 `HouseGenerator`（通用矩形房屋生成器）
   - **结果：所有建筑都变成类似的矩形房屋**

## 修复方案

### 1. 注册缺失的组件类型

在 `ComponentGeneratorRegistry` 中添加：

```java
// 注册 WALL_SEGMENT（复用 WallGenerator）
register("WALL_SEGMENT", new WallGenerator());

// 注册 CONNECTOR（临时复用 PathGenerator）
register("CONNECTOR", new PathGenerator());
register("BRIDGE", new PathGenerator());
```

### 2. 更新 StructureGeneratorAdaptor 映射

在 `mapComponentTypeToBuildingType` 中添加：

```java
case "WALL", "WALL_SEGMENT" -> BuildingType.WALL;
case "BRIDGE", "CONNECTOR" -> BuildingType.BRIDGE;
```

### 3. 修复 WallGenerator 的 styleProfile 获取

之前 `WallGenerator` 硬编码了 `styleProfile = "MEDIEVAL_CLASSIC"`，现在改为从 `SemanticComponent` 中获取：

```java
private String getStyleProfile(SemanticComponent semantic) {
    // 1. 优先使用 SemanticComponent 中的 styleProfile
    if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
        String profile = semantic.styleProfile().trim();
        // 映射 LLM 返回的风格名称
        String upper = profile.toUpperCase();
        if (upper.contains("CHINESE") && (upper.contains("GREAT") || upper.contains("WALL"))) {
            return "MEDIEVAL_CLASSIC"; // 长城风格
        }
        return profile;
    }
    // 2. 从 features 推断
    // 3. 默认
    return "MEDIEVAL_CLASSIC";
}
```

### 4. 增强日志

在 `SmartGeneratorRouter` 中添加更详细的日志：

```java
if (newSystemGenerator == null) {
    FormacraftMod.LOGGER.warn("SmartGeneratorRouter: no generator registered for component type: {}", componentType);
}
```

## 修复后的效果

### 修复前：
- `WALL_SEGMENT` → 找不到生成器 → 回退到 `HouseGenerator` → 生成矩形房屋
- `CONNECTOR` → 找不到生成器 → 回退到 `HouseGenerator` → 生成矩形房屋
- **结果：所有建筑都长得一样**

### 修复后：
- `WALL_SEGMENT` → 找到 `WallGenerator` → 生成正确的墙体
- `CONNECTOR` → 找到 `PathGenerator` → 生成连接结构
- **结果：根据用户描述生成不同的建筑**

## 已注册的组件类型

当前 `ComponentGeneratorRegistry` 中已注册的组件类型：

### 核心构件
- `TOWER` → `TowerGenerator`
- `KEEP` → `KeepGenerator`
- `WALL` → `WallGenerator`
- `WALL_SEGMENT` → `WallGenerator` ✅ **新增**
- `GATE` → `GateGenerator`
- `ROAD` → `RoadGenerator`

### 语义组件
- `MASS_MAIN` → `MassMainGenerator`
- `MASS_SECONDARY` → `MassMainGenerator`
- `ENTRANCE` → `EntranceGenerator`
- `SIGNAGE` → `SignageGenerator`
- `FACADE_WINDOWS` → `FacadeWindowsGenerator`
- `ROOF` → `RoofGenerator`
- `BALCONY` → `BalconyGenerator`
- `TERRACE` → `TerraceGenerator`
- `FOUNDATION` → `FoundationGenerator`
- `CHIMNEY` → `ChimneyGenerator`
- `DECOR_DETAIL` → `DecorDetailGenerator`

### 连接器
- `CONNECTOR` → `PathGenerator` ✅ **新增**
- `BRIDGE` → `PathGenerator` ✅ **新增**

## 后续改进建议

1. **创建专用的 CONNECTOR 生成器**：
   - 当前临时复用 `PathGenerator`
   - 后续可以创建 `ConnectorGenerator`，支持：
     - 桥（bridge）
     - 坡道（ramp）
     - 连廊（corridor）
     - 悬挑结构（overhang）

2. **完善 WALL_SEGMENT 生成器**：
   - 当前复用 `WallGenerator`
   - 可以增强支持：
     - 长城垛口（crenellations）
     - 城墙步道（walkway）
     - 防御工事（fortifications）

3. **动态注册机制**：
   - 支持运行时注册新的生成器
   - 支持插件式扩展

## 总结

通过注册缺失的组件类型，修复了"所有建筑长得一样"的问题。现在系统能够：

✅ 正确识别 LLM 返回的组件类型  
✅ 使用对应的生成器生成正确的结构  
✅ 根据用户描述生成不同的建筑  
✅ 提供更详细的日志用于调试  

