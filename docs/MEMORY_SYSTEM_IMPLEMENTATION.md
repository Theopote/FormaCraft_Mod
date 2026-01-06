# Forma-Cortex 记忆系统实现文档

## 概述

Forma-Cortex 记忆系统是 Formacraft 模组的核心功能之一，它让 AI 从"一次性工具"进化为"智能助手"。系统实现了三级记忆模型：空间记忆、语义记忆和基因记忆。

## 架构设计

### 三级记忆模型

1. **空间记忆 (Spatial Memory)**
   - 作用：解决"它在哪？"的问题
   - 实现：基于区块的索引 (`SpatialIndex`)
   - 功能：根据坐标快速检索建筑项目

2. **语义记忆 (Semantic Memory)**
   - 作用：解决"它是什么？"的问题
   - 实现：倒排索引 (`SemanticIndex`)
   - 功能：根据关键词、标签、描述检索建筑

3. **基因记忆 (Genetic Memory)**
   - 作用：解决"它长什么样？"的问题
   - 实现：存储完整的 `BuildingSpec` 和 `BuildingGenome`
   - 功能：保存建筑的所有生成参数，支持风格继承和修改

## 核心组件

### 1. ProjectMemory

**位置**: `src/main/java/com/formacraft/server/memory/ProjectMemory.java`

核心数据结构，包含：
- **基础信息**: UUID、名称、创建时间、最后修改时间
- **空间边界**: 最小/最大坐标、维度、关键点（锚点）
- **语义描述**: 描述文本、标签列表
- **基因数据**: 完整的 `BuildingSpec` 和 `BuildingGenome`
- **关联关系**: 连接的路径、父建筑群、子建筑

### 2. MemoryStorage

**位置**: `src/main/java/com/formacraft/server/memory/MemoryStorage.java`

负责记忆的持久化存储：
- 保存路径：`formacraft/memory/project_{uuid}.json`
- 支持保存、加载、删除、列表操作
- 自动创建目录结构

### 3. SpatialIndex

**位置**: `src/main/java/com/formacraft/server/memory/SpatialIndex.java`

空间索引实现：
- 基于区块坐标的快速检索
- 支持坐标查询 (`findAt`)
- 支持最近建筑查找 (`findNearest`)
- 线程安全的并发实现

### 4. SemanticIndex

**位置**: `src/main/java/com/formacraft/server/memory/SemanticIndex.java`

语义索引实现：
- 倒排索引：关键词 -> UUID 集合
- 支持 AND/OR 逻辑搜索
- 支持模糊搜索 (`searchContains`)
- 自动提取关键词（从名称、描述、标签、BuildingSpec）

### 5. MemoryManager

**位置**: `src/main/java/com/formacraft/server/memory/MemoryManager.java`

统一的记忆管理接口：
- 初始化：从磁盘加载所有记忆并构建索引
- 注册建筑：在建筑生成完成后自动保存
- 检索功能：空间检索、语义检索、名称查找
- 更新/删除：支持记忆的修改和删除

## 集成流程

### 1. 初始化

在 `ServerInitializer` 中，服务器启动后初始化记忆系统：

```java
ServerLifecycleEvents.SERVER_STARTED.register(server -> {
    MemoryManager memoryManager = new MemoryManager(server);
    memoryManager.initialize();
    BuildExecutionService.getInstance().setMemoryManager(memoryManager);
});
```

### 2. 自动保存

在 `BuildExecutionService` 中，当建造任务完成时自动保存记忆：

```java
if (memoryManager != null && !changes.isEmpty()) {
    // 从 PlayerSpecRepository 获取 BuildingSpec
    String buildingJson = PlayerSpecRepository.getBuildingJson(owner);
    BuildingSpec spec = JsonUtil.fromJson(buildingJson, BuildingSpec.class);
    
    // 注册到记忆系统
    ProjectMemory memory = memoryManager.registerBuilding(
        structure, spec, memoryName, world
    );
}
```

## 使用场景

### 场景 A：智能指代与修改

**玩家**: "把那个法师塔的屋顶改成蓝色的。"

**流程**:
1. LLM 解析：提取目标对象"法师塔"，操作"屋顶变蓝"
2. 记忆检索：`memoryManager.searchByKeywords("法师塔")` 或 `searchContains("法师塔")`
3. 命中记忆：找到 `绯红之尖 (bldg_007)`
4. 读取基因：从 `memory.getGeneData()` 获取 `BuildingSpec`
5. 修改参数：将 `palette.primary` 从 `red_nether_bricks` 改为 `warped_planks`
6. 重生成：在 `bounds` 范围内刷新建筑
7. 更新记忆：`memoryManager.updateMemory(memory)`

### 场景 B：风格继承与扩建

**玩家**: "在这里给它建一个附属的图书馆。"

**流程**:
1. 空间感知：检测玩家位置，`memoryManager.findAtPosition(playerPos)`
2. 基因提取：从相邻建筑获取 `geneData`
3. 参数混合：继承材质、屋顶风格，调整形态为图书馆
4. 生成：创建风格统一的附属建筑
5. 关联关系：设置 `relations.parentGroup` 或 `relations.children`

### 场景 C：多点导航

**玩家**: "修一条路，从这里通往绯红之尖的正门。"

**流程**:
1. 起点：`playerPos`
2. 终点检索：`memoryManager.findByName("绯红之尖")` -> `bounds.anchors.main_entrance`
3. 路径规划：调用 A* 算法生成路径

## 数据格式

### ProjectMemory JSON 示例

```json
{
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "name": "绯红之尖 (Crimson Spire)",
  "createdAt": "2023-10-27T10:00:00Z",
  "lastModified": "2023-10-28T14:30:00Z",
  
  "bounds": {
    "min": [90, 64, 90],
    "max": [110, 128, 110],
    "dimension": "minecraft:overworld",
    "anchors": {
      "main_entrance": [100, 65, 90],
      "balcony_view": [100, 90, 110]
    }
  },
  
  "description": "一座高耸的法师塔，居住着火系法师，顶部有燃烧的水晶。",
  "tags": ["Tower", "Magic", "Red", "Gothic"],
  
  "geneData": {
    "type": "TOWER",
    "style": "GOTHIC",
    "height": 64,
    "materials": {
      "wall": "minecraft:red_nether_bricks",
      "roof": "minecraft:magma_block"
    }
  },
  
  "relations": {
    "connectedTo": ["path_road_01"],
    "parentGroup": "Magic_City_District",
    "children": []
  }
}
```

## 性能优化

1. **索引缓存**: 所有记忆在内存中维护索引，避免频繁磁盘 I/O
2. **区块索引**: 空间检索只检查相关区块，而非全地图扫描
3. **倒排索引**: 语义检索使用倒排索引，O(1) 关键词查找
4. **延迟加载**: 索引在服务器启动时一次性加载，后续增量更新

## 未来扩展

### 1. 建筑师手记 (Architect's Journal)

游戏内物品，右键打开 UI：
- 显示所有已生成的建筑列表
- 传送功能：点击列表直接传送到建筑
- 重命名：玩家手动修改建筑名称
- 锁定：防止被 AI 误修改或覆盖
- 导出：一键导出建筑基因为共享文件

### 2. 向量数据库

对于更复杂的语义检索，可以集成轻量级向量数据库：
- 使用本地向量库（如 Chroma、FAISS）
- 支持语义相似度搜索
- 支持多语言查询

### 3. 记忆可视化

- 地图标记：在世界地图上显示所有建筑位置
- 关系图谱：可视化建筑之间的关联关系
- 时间线：显示建筑的创建和修改历史

## 总结

Forma-Cortex 记忆系统为 Formacraft 模组提供了对象持久性 (Object Permanence)，让 AI 能够：
- 记住所有生成的建筑
- 根据坐标或描述快速检索
- 支持智能指代和修改
- 实现风格继承和扩建
- 提供多点导航功能

这使 Formacraft 从"高级 /fill 命令"进化为"记得住事儿的皇家建筑师"。

