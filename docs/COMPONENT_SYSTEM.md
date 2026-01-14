# 🧩 FormaCraft Component System

**构件系统完整设计文档（v1）**

> *Component is not a block collection.*  
> *It is an architectural idea with memory, intent, and constraints.*

---

## 一、什么是 Component（构件）

在 FormaCraft 中，构件（Component）不是方块集合，而是：

**一个"可复用的建筑语义单元"**

它同时具备：

- ✅ **几何形态**（Geometry）
- ✅ **建筑语义**（Semantic Meaning）
- ✅ **放置约束**（Placement Rules）
- ✅ **变体能力**（Variant Potential）
- ✅ **可被 AI 理解与调用的接口**（Prompt / Socket）

### ❌ Component ≠ Structure / Schematic

| 对比项 | Schematic | Component |
|--------|-----------|-----------|
| 是否固定 | 是 | 否 |
| 是否理解语义 | 否 | 是 |
| 是否可变 | 只能整体缩放 | 可智能变体 |
| 是否可被 AI 调用 | 很差 | **核心用途** |
| 是否与上下文交互 | 否 | **强交互** |

---

## 二、为什么需要 Component 系统

FormaCraft 的目标不是：
> "帮你复制建筑"

而是：
> "帮助 AI 理解、组合、演化建筑语言"

### Component 系统解决的三个核心问题：

1. **建筑一致性**（风格、构法、细节不会乱）
2. **AI 可控性**（AI 不再直接 setBlock）
3. **用户参与度**（玩家自己的构建成为 AI 的素材）

---

## 三、Component 的核心设计原则（非常重要）

### 原则 1：Component 是「语义优先」

Component 的第一属性是 **它是什么**，不是 **它长什么样**

例如：
- "中式门洞"
- "哥特飞扶壁"
- "工业风钢桁架节点"

### 原则 2：Component 不是完整建筑

Component 是构建建筑的"器官"或"词汇"：
- 门
- 窗
- 栏杆
- 柱式
- 阳台
- 斗拱
- 雨棚
- 装饰构件

**建筑 = Skeleton × Component × Variant × Context**

### 原则 3：Component 必须 可被 AI 变体

AI 使用 Component 时，不能只是复制，而是：
- 改尺寸
- 改比例
- 改材质
- 改密度
- 改组合方式

但必须保持"识别性"

---

## 四、Component 的完整数据结构

### 概念层（设计视图）

```json
{
  "id": "chinese_dougong_basic",
  "name": "斗拱（基础型）",
  "category": "STRUCTURAL_DECOR",
  "tags": ["chinese", "dougong", "bracket"],

  "geometry": { ... },
  "placement": { ... },
  "sockets": [ ... ],
  "variants": { ... },
  "preview": { ... }
}
```

### 实现层（v1 实际结构）

```java
// src/main/java/com/formacraft/common/component/ComponentDefinition.java
public class ComponentDefinition {
    public String schema = "formacraft.component.v1";
    
    // 基础元数据
    public String id;                    // 唯一标识符（规范化）
    public String name;                  // 显示名称
    public ComponentCategory category;   // 分类
    public List<String> tags;            // 语义标签
    
    // 几何数据
    public Size size;                    // 包围盒尺寸
    public Anchor anchor;                // 锚点（原点）
    public List<BlockEntry> blocks;      // 方块数据
    
    // 语义数据
    public String culturalStyle;         // 文化风格
    public String archetype;             // 建筑原型
    
    // Socket 系统
    public List<ComponentSocket> sockets; // 插槽定义
    
    // 元信息
    public Long createdAtMs;
    public Long updatedAtMs;
    public String thumbnail;             // 缩略图文件名
}
```

---

## 五、Component 几何定义（Geometry）

### 设计目标

不是"存方块"，而是描述**结构逻辑**

### v1 实现方案

```json
{
  "size": {
    "width": 3,
    "height": 2,
    "depth": 3
  },
  "anchor": {
    "x": 1,
    "y": 0,
    "z": 1,
    "facing": "SOUTH"
  },
  "blocks": [
    {
      "dx": 0,
      "dy": 0,
      "dz": 0,
      "block": "minecraft:oak_planks",
      "semantic": "WALL"
    },
    {
      "dx": 1,
      "dy": 1,
      "dz": 0,
      "block": "minecraft:spruce_door[facing=south,half=lower]",
      "semantic": "DOOR"
    }
  ]
}
```

### 语义方块系统（Semantic Block）

每个方块可以标注语义部位：
- `WALL` - 墙体
- `FLOOR` - 地板
- `ROOF` - 屋顶
- `DOOR` - 门
- `WINDOW` - 窗
- `COLUMN` - 柱子
- `DETAIL` - 装饰细节

**这使得 AI 可以进行智能材质替换**

### 后续可升级为：

- 程序化生成
- 重复段定义
- 可裁剪段标记
- 参数化几何

---

## 六、PlacementSpec（放置语义规范）

这是 Component 系统的**核心创新**。

### 1️⃣ Attachment（附着类型）

定义构件如何与建筑结构关联：

| 类型 | 含义 | 示例 |
|------|------|------|
| `FREE` | 独立放置 | 柱子、雕塑 |
| `WALL` | 附着在墙体 | 门、窗、壁灯 |
| `EDGE` | 沿边缘放置 | 栏杆、檐口 |
| `ROOF` | 附着在屋顶 | 烟囱、天窗 |
| `FLOOR` | 附着在地面 | 地毯、地板装饰 |
| `CEILING` | 附着在天花板 | 吊灯、藻井 |

**实现状态：** ✅ 已实现 `AttachmentType` 枚举和识别器

```java
// src/main/java/com/formacraft/common/component/placement/AttachmentRecognizer.java
public enum AttachmentType {
    FLOOR, WALL, EDGE, ROOF, FREE
}
```

### 2️⃣ FacingPolicy（方向策略）

**不是所有构件都需要方向**

| 策略 | 适用构件 | 说明 |
|------|----------|------|
| `NONE` | 柱子、斗拱 | 无方向性 |
| `IN_OUT` | 门、窗洞口 | 内外朝向 |
| `AXIS` | 栏杆、墙段 | 沿轴线延伸 |
| `FREE` | 装饰 | 任意方向 |

✔ **完全符合对门窗 / 阳台 / 柱式的判断**

**实现状态：** ✅ 已实现 `FacingPolicy` 枚举

```java
// src/main/java/com/formacraft/common/component/socket/FacingPolicy.java
public enum FacingPolicy {
    NONE,      // 无方向（柱子）
    FIXED,     // 固定方向
    ALIGN,     // 对齐到表面
    FREE       // 自由旋转
}
```

### 3️⃣ Context Constraint（上下文约束）

```json
{
  "placement": {
    "allowedContexts": ["WALL"],
    "requireExterior": true,
    "edgeOnly": false,
    "minHeight": 3,
    "maxHeight": 5
  }
}
```

**实现状态：** 🚧 部分实现（通过 Socket 的 `context` 字段）

---

## 七、Socket 系统（组件连接的核心）

Socket 是 Component 的"接口"

它定义了：
- 可以放在哪
- 可以接什么
- 如何对齐

### Socket 的定义

```json
{
  "id": "wall_opening",
  "role": "MOUNT",
  "shape": "RECT",
  "context": "WALL",
  "facingPolicy": "ALIGN",
  "size": {
    "min": [2, 3],
    "max": [4, 5]
  },
  "tags": ["door", "window"]
}
```

### Socket 的实现结构

```java
// src/main/java/com/formacraft/common/component/socket/ComponentSocket.java
public class ComponentSocket {
    public String id;                    // Socket 标识
    public SocketRole role;              // MOUNT / SLOT
    public SocketShape shape;            // RECT / CIRCLE / POINT
    public SocketContext context;        // WALL / FLOOR / EDGE / etc.
    public FacingPolicy facingPolicy;    // 方向策略
    public SizeConstraint size;          // 尺寸约束
    public Set<String> tags;             // 语义标签
}
```

### Socket 的哲学意义

> 建筑不是随便堆的，是"插接"的

- 门**插进**墙
- 栏杆**贴在**边缘
- 阳台**挂在**立面
- 飞檐**沿着**屋顶生长

### Socket 匹配系统

**实现状态：** ✅ 已实现基础匹配逻辑

```java
// src/main/java/com/formacraft/common/component/group/PlayerComponentGroupExpander.java
// 根据 Socket 的 context 和 tags 进行智能匹配
```

---

## 八、Variant 系统（智能变化）

Component 的 Variant 不是随机，而是**受控变化**。

### 支持的变化类型

#### 1. 尺寸变化
- 高度拉伸
- 横向重复

#### 2. 结构裁剪
- 自动截断
- 保留特征段

#### 3. 材质语义替换
- stone → brick
- wood → concrete

### Variant 配置示例

```json
{
  "variants": {
    "scalable": ["X", "Y"],
    "repeatable": true,
    "materialSlots": {
      "PRIMARY": ["oak", "spruce"],
      "ACCENT": ["stone"]
    }
  }
}
```

**实现状态：** 🚧 规划中

当前已实现：
- ✅ 语义方块标注（`SemanticPart`）
- ✅ 语义风格配置（`SemanticStyleProfile`）
- 🚧 自动材质替换（待完善）

---

## 九、Component 拾取（ComponentTool）

### 用户如何定义 Component

1. 在世界中**手动建造**
2. 使用 `ComponentTool` 框选
3. 定义：
   - 正面 / 外侧
   - 附着面
   - 语义标签
4. 生成 Component JSON

### 方向不是"朝向"，而是"语义面"

这是关键思想：

| 构件 | 方向定义 |
|------|----------|
| 门 | 内 / 外 |
| 阳台 | 外部 |
| 柱子 | 无 |
| 栏杆 | 沿边 |

**这是比 Minecraft Facing 高一个层级的抽象。**

### ComponentTool 实现

**实现状态：** ✅ 已实现完整工具

```java
// src/main/java/com/formacraft/client/tool/ComponentTool.java
public class ComponentTool extends FormacraftTool {
    // 构件拾取
    public String buildCurrentComponentJson(MinecraftClient client);
    
    // Socket 配置
    public void addSocket();
    public void cycleSocketType();
    public void cycleSocketFacing();
    
    // 语义标注
    public void cycleSemanticStyle();
    public void cycleSemanticPart();
    
    // 预览与放置
    public void preview(MinecraftClient client);
    public void applyPatchPreview(MinecraftClient client);
}
```

### ComponentTool UI

**实现状态：** ✅ 已实现完整 UI 面板

- ✅ 构件名称、标签输入
- ✅ Socket 配置界面
- ✅ 语义标注选项
- ✅ 预览与保存按钮

---

## 十、Component Library（构件库）

### 核心理念

> 构件库是"建筑基因库"

### 存储特性

- ✅ 与世界 / 存档无关
- ✅ 文件级管理
- ✅ 可分享
- ✅ 可预览

### 文件结构

```
config/formacraft/components/
  ├─ catalog.json              # 构件目录索引
  ├─ chinese_dougong_basic.json
  ├─ chinese_dougong_basic.png  # 缩略图
  ├─ medieval_arch.json
  ├─ medieval_arch.png
  └─ modern_window.json
```

### 每个 Component 必须有：

- ✅ **缩略图**（Preview）- 64x64 等轴测视图
- ✅ **语义标签**（Tags）
- ✅ **Socket 描述**（Sockets）
- ✅ **分类信息**（Category）

### Component Library 实现

**实现状态：** ✅ 已完整实现

#### 存储系统
```java
// src/main/java/com/formacraft/common/component/ComponentStorage.java
public class ComponentStorage {
    // 全局组件库目录
    public static Path getGlobalComponentDir();
    
    // 保存构件
    public static void saveComponent(Path worldDir, ComponentDefinition def);
    
    // 加载构件目录
    public static ComponentCatalog loadCatalogWithSockets(Path worldDir);
}
```

#### 缩略图系统
```java
// src/main/java/com/formacraft/client/component/ComponentThumbnailGenerator.java
public class ComponentThumbnailGenerator {
    // 生成等轴测视图缩略图
    public static BufferedImage generateThumbnail(ComponentDefinition def);
}

// src/main/java/com/formacraft/client/component/ComponentThumbnailCache.java
public class ComponentThumbnailCache {
    // 缓存与读取缩略图
    public static Thumb getThumb(String componentId, int maxSize);
}
```

#### 构件库 UI
```java
// src/main/java/com/formacraft/client/ui/panel/ComponentLibraryPanel.java
public class ComponentLibraryPanel extends BasePanel {
    // ✅ 缩略图网格浏览
    // ✅ 搜索（id/name/tags）
    // ✅ 排序（最近保存 / 最近加载 / 名字 / 分类）
    // ✅ 双击加载构件
    // ✅ 分页显示
}
```

---

## 十一、Component × AI（PromptAssembler）

AI 不再生成方块，而是：

```json
{
  "use_components": [
    {
      "component": "chinese_dougong_basic",
      "count": "repeat_along_edge",
      "scale": { "y": 1.2 }
    }
  ]
}
```

AI 只回答：
- 用什么构件
- 在哪用
- 如何变体

### Prompt 系统实现

**实现状态：** ✅ 已实现 Prompt 注入

```java
// src/main/java/com/formacraft/ai/prompt/PromptAssembler.java
public class PromptAssembler {
    // 将构件库信息注入到 AI Prompt
    public String assemblePrompt(BuildContext ctx, PromptMode mode);
}
```

### Component 在 Prompt 中的表示

```
## Available Components

You have access to a component library. Use these pre-built components instead of generating blocks directly:

- **chinese_dougong_basic** (斗拱-基础型)
  - Category: STRUCTURAL_DECOR
  - Tags: chinese, dougong, bracket
  - Sockets: wall_mount (WALL, 2x3)
  
- **medieval_arch** (中世纪拱门)
  - Category: OPENING
  - Tags: medieval, arch, door
  - Sockets: wall_opening (WALL, 3x4)
```

---

## 十二、Component × Memory（进阶）

Component 可以被记忆系统引用：

```json
{
  "gene_data": {
    "components_used": [
      "chinese_dougong_basic",
      "red_gate_v2"
    ]
  }
}
```

于是 AI 可以：
> "在旁边建一个风格一致的附属建筑"

**实现状态：** 🚧 规划中

当前已实现：
- ✅ 构件使用记录（`ComponentLibraryUsage`）
- ✅ 最近加载时间追踪
- 🚧 基因数据系统（待实现）

---

## 十三、Component 系统的技术架构

### 客户端（Client）

```
ComponentTool (工具)
    ↓
ComponentLibraryPanel (UI)
    ↓
ComponentThumbnailCache (缓存)
    ↓
ClientComponentCatalogState (状态)
```

### 服务器端（Server）

```
ComponentStorage (存储)
    ↓
ComponentCatalog (目录)
    ↓
ComponentDefinition (定义)
```

### 网络协议

```java
// C2S: 保存构件
ComponentSavePayload(json, thumbnailPng)

// S2C: 下发构件目录
ComponentCatalogPayload(json)

// C2S: 请求构件定义
ComponentGetRequestPayload(id)

// S2C: 下发构件定义
ComponentDefinitionPayload(json)
```

---

## 十四、Component 系统的最终目标

当这套系统完成后，FormaCraft 将具备：

- 🧠 **建筑语言层**
- 🧬 **建筑基因继承**
- 🤖 **AI 可控生成**
- 🧱 **玩家参与建造逻辑**

> 玩家不再只是"下命令"  
> 而是在教 AI 如何建筑

---

## 十五、实现路线图

### ✅ Phase 1: 基础构件系统（已完成）

- [x] ComponentDefinition 数据结构
- [x] ComponentStorage 存储系统
- [x] ComponentTool 拾取工具
- [x] ComponentLibrary UI
- [x] 缩略图生成与缓存
- [x] 网络同步协议

### 🚧 Phase 2: Socket 系统（进行中）

- [x] ComponentSocket 基础结构
- [x] SocketType / SocketRole / SocketShape
- [x] AttachmentRecognizer
- [ ] Socket 匹配算法完善
- [ ] Socket 可视化预览
- [ ] 自动 Socket 检测

### 🔜 Phase 3: Variant 系统（规划中）

- [ ] 材质语义替换引擎
- [ ] 尺寸自适应算法
- [ ] 重复段识别与生成
- [ ] 结构裁剪逻辑

### 🔜 Phase 4: AI 集成（规划中）

- [x] Prompt 注入基础
- [ ] Component 调用语法
- [ ] AI 变体参数生成
- [ ] 智能 Socket 匹配

---

## 十六、开发者指南

### 如何添加新的 Component

1. **在游戏中建造**
   ```
   使用 SelectionTool 框选区域
   切换到 ComponentTool
   设置 Anchor 点
   ```

2. **配置语义信息**
   ```
   输入构件名称
   添加标签（tags）
   选择分类（category）
   标注语义部位（semantic）
   ```

3. **配置 Socket（可选）**
   ```
   选择 Socket 类型
   设置尺寸约束
   定义朝向策略
   添加 Socket 标签
   ```

4. **保存构件**
   ```
   点击"保存为构件"
   系统自动生成缩略图
   构件添加到全局库
   ```

### 如何使用 Component

1. **浏览构件库**
   ```
   打开 Component Library 面板
   搜索 / 过滤 / 排序
   查看缩略图
   ```

2. **加载构件**
   ```
   单击选中构件
   双击直接加载
   或点击"加载构件"按钮
   ```

3. **放置构件**
   ```
   设置放置锚点
   预览效果
   应用 Patch（支持 Undo/Redo）
   ```

---

## 十七、总结（一句话）

> **Component 是 FormaCraft 的"词汇表"，**  
> **Socket 是它的"语法"，**  
> **Variant 是它的"修辞"，**  
> **AI 是它的"作家"。**

---

## 附录 A：核心类索引

### 数据结构
- `ComponentDefinition` - 构件定义
- `ComponentSocket` - 插槽定义
- `ComponentCatalog` - 构件目录
- `SemanticPart` - 语义部位

### 工具系统
- `ComponentTool` - 构件工具
- `SelectionTool` - 选区工具
- `ComponentToolState` - 工具状态

### 存储系统
- `ComponentStorage` - 存储管理
- `ComponentThumbnailGenerator` - 缩略图生成
- `ComponentThumbnailCache` - 缩略图缓存

### UI 系统
- `ComponentLibraryPanel` - 构件库面板
- `ToolPanel` - 工具面板
- `BasePanel` - 面板基类

### 网络协议
- `ComponentSavePayload` - 保存构件
- `ComponentCatalogPayload` - 构件目录
- `ComponentDefinitionPayload` - 构件定义

### AI 集成
- `PromptAssembler` - Prompt 组装器
- `BuildContext` - 构建上下文

---

## 附录 B：配置文件示例

### 完整的 Component JSON

```json
{
  "schema": "formacraft.component.v1",
  "id": "chinese_dougong_basic",
  "name": "斗拱（基础型）",
  "category": "STRUCTURAL_DECOR",
  "tags": ["chinese", "dougong", "bracket", "traditional"],
  
  "culturalStyle": "CHINESE",
  "archetype": "STRUCTURAL_ORNAMENT",
  
  "size": {
    "width": 5,
    "height": 3,
    "depth": 3
  },
  
  "anchor": {
    "x": 2,
    "y": 0,
    "z": 1,
    "facing": "SOUTH"
  },
  
  "blocks": [
    {
      "dx": 0,
      "dy": 0,
      "dz": 0,
      "block": "minecraft:oak_log[axis=x]",
      "semantic": "COLUMN"
    },
    {
      "dx": 1,
      "dy": 1,
      "dz": 0,
      "block": "minecraft:oak_stairs[facing=east]",
      "semantic": "DETAIL"
    }
  ],
  
  "sockets": [
    {
      "id": "wall_mount",
      "role": "MOUNT",
      "shape": "RECT",
      "context": "WALL",
      "facingPolicy": "ALIGN",
      "size": {
        "min": [3, 2],
        "max": [5, 3]
      },
      "tags": ["structural", "decorative"]
    }
  ],
  
  "createdAtMs": 1704067200000,
  "updatedAtMs": 1704067200000,
  "thumbnail": "chinese_dougong_basic.png"
}
```

---

**文档版本：** v1.0  
**最后更新：** 2026-01  
**维护者：** FormaCraft Development Team

---

*这份文档是 FormaCraft Component 系统的核心设计哲学与实现指南。*  
*它既是开发者的技术规范，也是高级用户的使用手册。*  
*随着系统的演进，本文档将持续更新。*
