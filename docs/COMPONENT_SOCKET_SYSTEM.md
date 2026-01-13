# Component Socket System v1

## 📌 核心定义

**Socket** = 「组件可以被放置/连接/生长的语义接口」

它是 Component × Component、Component × Skeleton、Component × AI 决策的**唯一合法连接点**。

---

## 🎯 为什么需要 Socket

### 已有系统（✅）

- Component（原型 + 变体）
- PlacementSpec（内外、边缘、附着面）
- Variant 编译器（结构不会乱变）

### 缺失环节（❌）

- **组件之间"如何对齐/嵌合"的规则**

### 没有 Socket 会出现的问题

- 门不知道该插哪
- 栏杆不知道什么算"边缘"
- 阳台不知道该挂在哪
- 路径/城墙无法连续生长

---

## 🏗️ Socket 系统职责边界

### ✅ Socket 只解决 3 件事

1. **能不能放**（合法性）
2. **放在哪里**（定位）
3. **怎么对齐**（朝向/尺寸/约束）

### ❌ 不做

- 方块生成
- AI 推理
- UI 操作

---

## 📊 核心数据结构

### ComponentSocket（组件接口）

```java
public final class ComponentSocket {
    public final String id;                      // 例如 "door_opening"
    public final SocketRole role;                 // PROVIDER / CONSUMER
    public final SocketShape shape;               // RECT / LINE / POINT / RING
    public final SocketContext context;           // WALL / EDGE / ROOF / GROUND
    public final SocketFacingPolicy facingPolicy; // NONE / IN_OUT / AXIS / FREE
    public final SizeConstraint size;             // min/max 约束
    public final Set<String> tags;                // 语义标签
}
```

### 枚举定义

#### SocketRole（角色）

| 角色 | 含义 | 示例 |
|------|------|------|
| `PROVIDER` | 提供接口 | 墙体提供门洞、屋顶提供烟囱位 |
| `CONSUMER` | 消耗接口 | 门需要墙体洞口、烟囱需要屋顶位 |

**匹配规则**：PROVIDER × CONSUMER = 可匹配

#### SocketShape（形状）

| 形状 | 维度 | 示例 |
|------|------|------|
| `POINT` | 0D | 灯、装饰、旗帜 |
| `LINE` | 1D | 栏杆、飞檐、边缘装饰 |
| `RECT` | 2D | 门、窗、阳台、壁画 |
| `RING` | 1D | 圆窗、斗拱圈、拱门 |

#### SocketContext（上下文）

| 上下文 | 含义 | 示例 |
|--------|------|------|
| `WALL` | 墙面 | 门、窗、壁画、壁灯 |
| `EDGE` | 边缘 | 栏杆、飞檐、檐口装饰 |
| `CORNER` | 角落 | 角柱、角装饰、转角斗拱 |
| `ROOF` | 屋顶 | 烟囱、天窗、屋脊装饰 |
| `GROUND` | 地面 | 台阶、地砖、地灯 |
| `INTERIOR` | 室内 | 吊灯、内墙装饰、家具 |

#### SocketFacingPolicy（朝向策略）

| 策略 | 含义 | 示例 |
|------|------|------|
| `NONE` | 无朝向 | 柱子、吊灯、地砖 |
| `IN_OUT` | 内外朝向 | 门、窗、阳台（只关心哪边是内/外） |
| `AXIS` | 轴向对齐 | 栏杆、墙段、路径（只关心 X 或 Z 轴） |
| `FREE` | 自由朝向 | 旗帜、路标、装饰 |

---

## 🔄 Socket 匹配算法

### SocketMatcher（核心）

```java
public static boolean canMatch(ComponentSocket provider, ComponentSocket consumer) {
    // 1. role：必须是 PROVIDER × CONSUMER
    if (provider.role != SocketRole.PROVIDER) return false;
    if (consumer.role != SocketRole.CONSUMER) return false;

    // 2. context：必须相同
    if (provider.context != consumer.context) return false;

    // 3. shape：必须相同
    if (provider.shape != consumer.shape) return false;

    // 4. tags：Provider 必须包含 Consumer 的所有 tags
    if (!provider.hasAllTags(consumer.tags)) return false;

    // 5. size：尺寸约束必须有交集
    if (!provider.size.compatibleWith(consumer.size)) return false;

    return true;
}
```

### 匹配规则（全部满足才算匹配）

1. **role**：PROVIDER × CONSUMER
2. **context**：相同（WALL 只能匹配 WALL）
3. **shape**：相同（RECT 只能匹配 RECT）
4. **tags**：Provider 包含 Consumer 的所有 tags
5. **size**：尺寸约束有交集

---

## 📐 Socket 使用示例

### 示例 1：墙体 × 门

**墙体组件（PROVIDER）**：

```json
{
  "id": "stone_wall",
  "sockets": [
    {
      "id": "wall_opening",
      "role": "PROVIDER",
      "shape": "RECT",
      "context": "WALL",
      "facingPolicy": "IN_OUT",
      "size": { "min": [1, 2], "max": [4, 5] },
      "tags": ["opening", "door", "window"]
    }
  ]
}
```

**门组件（CONSUMER）**：

```json
{
  "id": "gothic_door",
  "sockets": [
    {
      "id": "door_mount",
      "role": "CONSUMER",
      "shape": "RECT",
      "context": "WALL",
      "facingPolicy": "IN_OUT",
      "size": { "min": [2, 3], "max": [3, 5] },
      "tags": ["door"]
    }
  ]
}
```

**匹配结果**：

- ✅ role：PROVIDER × CONSUMER
- ✅ context：WALL = WALL
- ✅ shape：RECT = RECT
- ✅ tags：["opening","door","window"] 包含 ["door"]
- ✅ size：[1-4 × 2-5] 与 [2-3 × 3-5] 有交集 [2-3 × 3-5]

**→ 可以匹配！**

### 示例 2：屋顶 × 栏杆（不匹配）

**屋顶（PROVIDER）**：

```json
{
  "context": "ROOF",
  "shape": "POINT"
}
```

**栏杆（CONSUMER）**：

```json
{
  "context": "EDGE",
  "shape": "LINE"
}
```

**匹配结果**：

- ❌ context：ROOF ≠ EDGE

**→ 不匹配！**

---

## 🔌 集成到现有系统

### 1. ComponentPrototype（原型）

```java
public class ComponentPrototype {
    // ... 现有字段 ...
    
    /**
     * Socket 接口列表（v1）：定义组件可以被放置/连接的语义接口。
     */
    public List<ComponentSocket> sockets;
}
```

### 2. SocketFinder（查找器）

```java
// 在世界/选区中查找匹配的 Provider Socket
List<SocketPlacement> slots = SocketFinder.findProviders(
    world,
    searchBox,
    consumer
);

// 选择最优位置
SocketPlacement chosen = pickBest(slots);

// 编译变体
List<BlockPatch> patches = ComponentVariantCompiler.compile(
    doorPrototype,
    variant,
    chosen.origin(),
    chosen.facing(),
    styleProfile
);
```

### 3. SocketAwareTool（工具接口）

```java
public interface SocketAwareTool {
    /**
     * 获取当前激活的组件原型（用于 Socket 匹配）。
     */
    ComponentPrototype getActivePrototype();

    /**
     * 是否启用 Socket 高亮（可选）。
     */
    default boolean isSocketHighlightEnabled() {
        return true;
    }
}
```

**渲染高亮（伪代码）**：

```java
if (tool instanceof SocketAwareTool sat) {
    ComponentPrototype proto = sat.getActivePrototype();
    if (proto != null && proto.sockets != null) {
        for (ComponentSocket consumer : proto.sockets) {
            if (consumer.role == SocketRole.CONSUMER) {
                List<SocketPlacement> providers = SocketFinder.findProviders(...);
                for (SocketPlacement p : providers) {
                    renderSocketHighlight(p, canMatch ? GREEN : RED);
                }
            }
        }
    }
}
```

### 4. AI Prompt 集成（未来）

```json
{
  "available_sockets": [
    {
      "id": "wall_opening",
      "context": "WALL",
      "shape": "RECT",
      "size": [2, 3],
      "tags": ["door", "window"]
    }
  ]
}
```

**AI 输出**：

```json
{
  "component": "gothic_door",
  "socket_id": "wall_opening"
}
```

---

## 🎯 你的 5 点判断 × Socket 支持

| 你的判断 | Socket 如何支持 |
|----------|----------------|
| 门窗只要内外 | `facingPolicy = IN_OUT` |
| 阳台只能外部 | `context = WALL` + exterior-only socket |
| 柱子无方向 | `facingPolicy = NONE` |
| 栏杆只在边缘 | `context = EDGE` + `shape = LINE` |
| 装饰贴墙/特定区 | `tags + context + shape` |

---

## 📋 完整流程示例

### 场景：AI 放置一个门

1. **AI 决策**

   ```
   AI: "在这堵墙上放一个哥特门"
   ```

2. **加载组件**

   ```java
   ComponentPrototype doorProto = ComponentModelApi.loadPrototype("gothic_door");
   ComponentSocket consumer = doorProto.sockets.stream()
       .filter(s -> s.role == SocketRole.CONSUMER)
       .findFirst().orElse(null);
   ```

3. **查找可用 Socket**

   ```java
   List<SocketPlacement> slots = SocketFinder.findProviders(
       world, 
       selection, 
       consumer
   );
   ```

4. **选择最优位置**

   ```java
   SocketPlacement chosen = SocketFinder.sortByScore(
       slots, 
       providerSocket, 
       consumer, 
       playerPos
   ).get(0);
   ```

5. **编译变体**

   ```java
   List<BlockPatch> patches = ComponentVariantCompiler.compile(
       doorProto,
       variant,
       chosen.origin(),
       chosen.facing(),
       styleProfile
   );
   ```

6. **落地方块**

   ```java
   PatchApplicator.apply(world, patches);
   ```

---

## 🔧 v1 实现状态

### ✅ 已完成

- ComponentSocket（数据结构 + Builder）
- SocketRole / SocketShape / SocketContext / SocketFacingPolicy（枚举）
- SizeConstraint（尺寸约束）
- SocketMatcher（匹配算法 + 评分）
- SocketPlacement（输出结构）
- SocketFinder（接口骨架，v2 补齐实现）
- ComponentPrototype.sockets（字段集成）
- SocketAwareTool（工具接口）

### 🔧 v2 扩展

- SocketFinder 实现（与 Component Instance Storage 集成）
- 从 Skeleton 节点提取 Socket
- 从选区边界启发式推断 Socket
- Socket 高亮渲染（客户端）
- AI Prompt 集成（PromptAssembler）

---

## 📚 相关文档

- [Component System Architecture](./COMPONENT_SYSTEM_ARCHITECTURE.md)
- [Variant Rules Specification](./VARIANT_RULES_SPEC.md)
- [Placement Specification](./COMPONENT_PLACEMENT.md)

---

**版本**：v1.0  
**最后更新**：2026-01-13  
**维护者**：Formacraft Team
