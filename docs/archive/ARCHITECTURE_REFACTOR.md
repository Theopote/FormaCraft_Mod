# FormaCraft BuildingSpec 架构重构说明

## 重构概述

根据最佳实践建议，已将 BuildingSpec 系统重构为更规范、可扩展的架构。

## 新的包结构

```
com.formacraft.common.model.build
├── BuildingSpec.java      # 核心建筑规格类
├── BuildingType.java      # 建筑类型枚举
├── BuildingStyle.java     # 建筑风格枚举
├── Footprint.java         # 占地结构
├── Materials.java         # 材质结构
└── Features.java          # 功能特性

com.formacraft.common.json
└── JsonUtil.java          # 统一的 JSON 工具类
```

## 主要改进

### 1. 使用枚举类型
- **BuildingType**: HOUSE, TOWER, BRIDGE, CASTLE, WALL, CUSTOM
- **BuildingStyle**: MEDIEVAL, MODERN, ASIAN, FUTURISTIC, RUSTIC, DEFAULT

### 2. 结构化的 Footprint
- 支持 rectangle、circle、polygon 等形状
- 统一的尺寸管理（width, depth, radius）

### 3. 完善的 Materials 和 Features
- Materials: wall, roof, floor, window, foundation
- Features: hasWindows, hasStairs, hasDoor, hasBalcony, hasRoof, hasRoofDecoration

### 4. 统一的 JsonUtil
- 替代了原来的 FormaJson
- 支持所有类型的序列化/反序列化
- 使用 Gson，完全兼容 Fabric

### 5. 扩展性支持
- BuildingSpec 包含 `extra` Map，支持 AI 动态添加参数
- 便捷方法：getWidth(), getDepth(), getRadius() 自动从 footprint 提取

## 使用示例

### 从 Python 返回的 JSON 解析 BuildingSpec

```java
String response = httpResponse.body();
BuildingSpec spec = JsonUtil.fromJson(response, BuildingSpec.class);

// 使用生成器
StructureGenerator generator = StructureGeneratorFactory.getGenerator(spec);
GeneratedStructure structure = generator.generate(spec, origin, world);
```

### 创建 BuildingSpec

```java
BuildingSpec spec = new BuildingSpec();
spec.setType(BuildingType.HOUSE);
spec.setStyle(BuildingStyle.MEDIEVAL);
spec.setHeight(10);
spec.setFloors(2);

Footprint footprint = new Footprint(8, 6);
spec.setFootprint(footprint);

Materials materials = new Materials();
materials.setWall("minecraft:stone_bricks");
materials.setRoof("minecraft:dark_oak_planks");
spec.setMaterials(materials);

Features features = new Features();
features.setHasWindows(true);
features.setWindowCount(4);
spec.setFeatures(features);
```

## 迁移说明

所有使用旧 `com.formacraft.common.model.BuildingSpec` 的地方已更新为：
- `com.formacraft.common.model.build.BuildingSpec`
- 所有字段访问从 `spec.field` 改为 `spec.getField()`
- 所有 `FormaJson` 调用已改为 `JsonUtil`

## 兼容性

- ✅ 完全兼容 Fabric 1.21.10
- ✅ 与 Python 后端的 JSON 格式兼容
- ✅ 向后兼容（旧的 JSON 格式仍可解析）

