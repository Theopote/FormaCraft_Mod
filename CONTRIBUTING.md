# Contributing to Formacraft

感谢你对 Formacraft 项目的兴趣！

---

## 1. Project Goals (Read First)

Formacraft 的目标不是：

- ❌ 追求"最快生成"
- ❌ 模拟真实 CAD
- ❌ 强制完美建筑

而是：

- ✅ **可解释** - 每个决策都有语义依据
- ✅ **可修正** - Preview + Undo/Redo 支持
- ✅ **可演化** - Memory 系统支持建筑演化
- ✅ **可协作** - 清晰的架构和扩展点

---

## 2. Contribution Areas

你可以贡献于：

- 🧠 **AI / Prompt 设计** - 改进 LLM 提示词、输出格式
- 🧱 **Generator / Geometry** - 新的 Skeleton 生成器
- 🧩 **Component & Socket 系统** - 新构件、Socket 提供者
- 🛠 **Tool / UI / UX** - 新工具、UI 改进
- 📚 **文档 / 示例 / 测试** - 完善文档、添加示例

---

## 3. Coding Principles

### 3.1 Never SetBlock Directly

❌ **禁止**：

```java
world.setBlockState(pos, blockState, 3);
```

✅ **必须**：

```java
List<BlockPatch> patches = new ArrayList<>();
patches.add(new BlockPatch("place", dx, dy, dz, "minecraft:stone"));
PatchExecutor.apply(world, origin, patches);
```

**原因**：只有通过 Patch 系统，才能支持 Preview、Undo、工具裁剪等功能。

### 3.2 Everything Must Be Previewable

任何会影响世界的逻辑：

- ✅ 必须能 Preview
- ✅ 必须可撤销
- ✅ 必须受工具约束

### 3.3 Memory-Safe Changes

修改建筑时：

- ✅ 必须读取 Memory
- ✅ 修改 `gene_data`
- ✅ 更新 Memory JSON

**位置**: `com.formacraft.server.memory.MemoryManager`

---

## 4. Adding a New Tool

### 步骤 1: 实现 FormacraftTool

```java
public class MyCustomTool implements FormacraftTool {
    @Override
    public void onActivate(PlayerEntity player) {
        // 工具激活时的逻辑
    }
    
    @Override
    public void contributeToPrompt(PromptContext ctx) {
        // 将工具状态注入 Prompt
        ctx.addConstraint("my_tool", getMyToolState());
    }
    
    @Override
    public void render(MatrixStack matrices, Camera camera) {
        // 渲染工具可视化
    }
}
```

### 步骤 2: 注册工具

```java
// 在 BuiltinToolsEntrypoint 中注册
@FormacraftToolEntrypoint
public class BuiltinToolsEntrypoint {
    public static void register() {
        ToolManager.register(new MyCustomTool());
    }
}
```

### 步骤 3: 提供 Socket（可选）

```java
public class MyToolSocketProvider implements ToolBasedSocketProvider {
    @Override
    public List<Socket> provide(World world, SocketQueryContext ctx) {
        // 从工具状态生成 Socket
        return generateSocketsFromToolState(ctx);
    }
}
```

**参考**: `com.formacraft.client.tool.*`

---

## 5. Adding a New Component

### 步骤 1: 定义 Component JSON

```json
{
  "schema": "formacraft.component.v1",
  "id": "my_custom_component",
  "name": "我的自定义构件",
  "category": "GENERIC",
  "tags": ["custom", "decorative"],
  "size": { "w": 3, "h": 2, "d": 3 },
  "anchor": { "dx": 1, "dy": 0, "dz": 1, "facing": "NORTH" },
  "placementSpec": {
    "attachment": "WALL",
    "spatialContext": "EXTERIOR",
    "facingPolicy": "DERIVED_FROM_HOST"
  },
  "blocks": [
    { "dx": 0, "dy": 0, "dz": 0, "block": "minecraft:stone" }
  ]
}
```

### 步骤 2: 配置 PlacementSpec

```java
ComponentPlacementSpec spec = new ComponentPlacementSpec();
spec.attachment = AttachmentType.WALL;
spec.spatialContext = SpatialContext.EXTERIOR;
spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
spec.allowedSockets.add(SocketType.WALL_SURFACE);
```

### 步骤 3: 自动进入 AI 构件库

构件定义后，会自动：

- ✅ 注册到 `ComponentCatalog`
- ✅ 可被 `ComponentQuery` 查询
- ✅ AI 可以在 Blueprint 中使用

**参考**: `com.formacraft.common.component.*`

---

## 6. Adding a New Skeleton

### 步骤 1: 扩展 SkeletonType

```java
enum SkeletonType {
    // ... 现有类型
    MY_CUSTOM_SKELETON  // 新增
}
```

### 步骤 2: 创建 SkeletonPlan 子类

```java
public class MyCustomSkeletonPlan extends SkeletonPlan {
    @Override
    public SkeletonType type() {
        return SkeletonType.MY_CUSTOM_SKELETON;
    }
}
```

### 步骤 3: 实现 Generator

```java
public class MyCustomSkeletonGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(
        GenerationContext ctx,
        ExecutableSkeletonPlan plan
    ) {
        List<BlockPatch> patches = new ArrayList<>();
        // ... 生成逻辑
        return patches;
    }
}
```

### 步骤 4: 注册 Generator

```java
SkeletonGeneratorRegistry.register(
    SkeletonType.MY_CUSTOM_SKELETON,
    new MyCustomSkeletonGenerator()
);
```

**参考**: `com.formacraft.common.skeleton.*`

---

## 7. Style & Naming

### 类名

- ✅ 语义优先（不是 Block）
- ✅ 清晰表达用途
- ❌ 避免缩写（除非是通用术语）

**示例**：
- ✅ `ComponentPlacementSpec`
- ✅ `SocketMatcher`
- ❌ `CPS`、`SM`

### 包名

按系统分层：

```
com.formacraft.
  ├── common.          # 通用逻辑
  │   ├── patch.       # Patch 系统
  │   ├── component.  # 构件系统
  │   └── skeleton.   # Skeleton 系统
  ├── client.          # 客户端逻辑
  │   ├── tool.       # 工具系统
  │   └── ui.         # UI 系统
  └── server.          # 服务端逻辑
      ├── build.      # 建造执行
      └── memory.     # Memory 系统
```

### 注释

- ✅ 解释 **为什么**，而不是 **怎么做**
- ✅ 复杂逻辑必须注释
- ❌ 避免显而易见的注释

**示例**：

```java
// ✅ 好注释
// 必须通过 Patch 系统，才能支持 Preview 和 Undo
PatchExecutor.apply(world, origin, patches);

// ❌ 坏注释
// 应用 Patch
PatchExecutor.apply(world, origin, patches);
```

---

## 8. Pull Request Checklist

在提交 PR 前，请确认：

- ✅ **不直接修改世界** - 所有修改都通过 Patch
- ✅ **Patch 可 Preview** - 所有结果都可预览
- ✅ **不破坏 Tool 约束** - 工具约束必须被遵守
- ✅ **通过基本生成测试** - 至少测试一种建筑类型
- ✅ **文档同步更新** - 如有架构变更，更新相关文档

### PR 模板

```markdown
## 变更类型
- [ ] 新功能
- [ ] Bug 修复
- [ ] 文档更新
- [ ] 重构

## 描述
简要描述变更内容

## 测试
- [ ] 已测试基本功能
- [ ] 已测试边界情况
- [ ] 已测试 Undo/Redo

## 相关 Issue
Closes #xxx
```

---

## 9. Development Setup

### 前置要求

- Java 21+
- Python 3.12+
- Gradle 8.0+
- Minecraft 1.21.10

### 启动步骤

1. **启动 Python 后端**：
```bash
cd python_backend
pip install -r requirements.txt
uvicorn app.main:app --reload
```

2. **构建 Java 模组**：
```bash
./gradlew build
```

3. **运行 Minecraft（开发环境）**：
```bash
./gradlew runClient
```

### 调试技巧

- 查看 Prompt：在 `PromptAssembler` 中添加日志
- 查看 Patch：在编译器中添加日志
- 查看 Memory：在 `MemoryManager` 中添加日志

---

## 10. Final Words

> **Formacraft is designed to grow.**

不要求你一次做完，只要求你 **不要破坏语义边界**。

### 核心原则回顾

1. **Never SetBlock Directly** - 所有修改都通过 Patch
2. **Everything Must Be Previewable** - 所有结果都可预览
3. **Memory-Safe Changes** - 修改建筑时更新 Memory

### 需要帮助？

- 📖 阅读 [ARCHITECTURE.md](ARCHITECTURE.md) 了解系统架构
- 📚 阅读 [FORMACRAFT_DEVELOPER_DOCUMENTATION.md](docs/FORMACRAFT_DEVELOPER_DOCUMENTATION.md) 了解详细实现
- 💬 在 Issue 中提问

---

**感谢你的贡献！** 🎉
