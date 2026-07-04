# Component Assembler 使用指南

## 快速开始

### 1. 初始化系统

在 mod 初始化时调用：

```java
// 在 ServerInitializer.onInitializeServer() 或 FormacraftMod.onInitialize()
ComponentAssemblerRegistry.registerDefaults();
DefaultPalettes.bootstrap();
```

### 2. 创建组件计划

```java
// 创建组件计划
ComponentPlan components = new ComponentPlan()
    .add(new ComponentSpec(ComponentType.ENCLOSING_WALL)
        .size(0, 0, 8)  // 高度 8
        .tag("defensive"))
    .add(new ComponentSpec(ComponentType.TOWER)
        .offset(10, 0, 10)  // 相对 Skeleton 原点的偏移
        .size(5, 5, 15)  // 宽度 5，深度 5，高度 15
        .tag("corner"))
    .add(new ComponentSpec(ComponentType.COURTYARD)
        .size(12, 12, 0)  // 宽度 12，深度 12
        .tag("inner"));
```

### 3. 创建骨架计划

```java
// 创建骨架计划
ExecutableSkeletonPlan skeleton = new ExecutableSkeletonPlan(SkeletonType.RADIAL_RING)
    .put("radius", 15)
    .put("conformTerrain", true);
```

### 4. 生成 BlockPatch

```java
// 生成 BlockPatch
GenerationContext ctx = new GenerationContext(world, origin, new Random(), 200_000);
List<BlockPatch> patches = ComponentBuildPipeline.buildComponentAsPatch(
    ctx,
    skeleton,
    components,
    "DEFAULT",  // paletteId
    origin
);
```

### 5. 预览和应用

```java
// 客户端预览
BuildConfirmPanel.INSTANCE.showPatchPreview(origin, patches);

// 或直接应用
PatchExecutor.apply(world, origin, patches);
```

## LLM 输出格式

### JSON 格式示例

```json
{
  "skeleton": {
    "type": "RADIAL_RING",
    "radius": 15,
    "conformTerrain": true
  },
  "components": [
    {
      "type": "ENCLOSING_WALL",
      "height": 8,
      "tags": ["defensive", "outer"]
    },
    {
      "type": "TOWER",
      "offsetX": 10,
      "offsetY": 0,
      "offsetZ": 10,
      "width": 5,
      "depth": 5,
      "height": 15,
      "tags": ["corner", "defensive"]
    },
    {
      "type": "COURTYARD",
      "width": 12,
      "depth": 12,
      "tags": ["inner", "public"]
    }
  ]
}
```

### Python 解析示例

```python
def parse_component_plan(data):
    skeleton_data = data.get("skeleton", {})
    components_data = data.get("components", [])
    
    # 创建骨架计划
    skeleton = ExecutableSkeletonPlan(
        SkeletonType[skeleton_data["type"]]
    )
    for key, value in skeleton_data.items():
        if key != "type":
            skeleton.put(key, value)
    
    # 创建组件计划
    components = ComponentPlan()
    for comp_data in components_data:
        comp_type = ComponentType[comp_data["type"]]
        spec = ComponentSpec(comp_type)
        
        if "offsetX" in comp_data:
            spec.offset(
                comp_data.get("offsetX", 0),
                comp_data.get("offsetY", 0),
                comp_data.get("offsetZ", 0)
            )
        
        if "width" in comp_data:
            spec.size(
                comp_data.get("width", 0),
                comp_data.get("depth", 0),
                comp_data.get("height", 0)
            )
        
        for tag in comp_data.get("tags", []):
            spec.tag(tag)
        
        components.add(spec)
    
    return skeleton, components
```

## 完整示例：土楼

```java
// 创建土楼结构
ExecutableSkeletonPlan skeleton = new ExecutableSkeletonPlan(SkeletonType.RADIAL_RING)
    .put("radius", 20)
    .put("conformTerrain", true);

ComponentPlan components = new ComponentPlan()
    // 外墙
    .add(new ComponentSpec(ComponentType.ENCLOSING_WALL)
        .size(0, 0, 12)
        .tag("defensive")
        .tag("outer"))
    // 内院
    .add(new ComponentSpec(ComponentType.COURTYARD)
        .size(15, 15, 0)
        .tag("inner")
        .tag("public"))
    // 四个角楼
    .add(new ComponentSpec(ComponentType.TOWER)
        .offset(18, 0, 0)
        .size(4, 4, 15)
        .tag("corner")
        .tag("defensive"))
    .add(new ComponentSpec(ComponentType.TOWER)
        .offset(-18, 0, 0)
        .size(4, 4, 15)
        .tag("corner")
        .tag("defensive"))
    .add(new ComponentSpec(ComponentType.TOWER)
        .offset(0, 0, 18)
        .size(4, 4, 15)
        .tag("corner")
        .tag("defensive"))
    .add(new ComponentSpec(ComponentType.TOWER)
        .offset(0, 0, -18)
        .size(4, 4, 15)
        .tag("corner")
        .tag("defensive"));

// 生成
List<BlockPatch> patches = ComponentBuildPipeline.buildComponentAsPatch(
    ctx, skeleton, components, "DEFAULT", origin
);
```

## 扩展：添加新组件类型

### 步骤 1：实现装配器

```java
public class GateAssembler implements ComponentAssembler {
    @Override
    public List<SemanticPlacementOp> assemble(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentSpec component
    ) {
        List<SemanticPlacementOp> ops = new ArrayList<>();
        
        // 实现门的具体逻辑
        // ...
        
        return ops;
    }
}
```

### 步骤 2：注册装配器

```java
ComponentAssemblerRegistry.register(ComponentType.GATE, new GateAssembler());
```

### 步骤 3：使用

```java
ComponentPlan plan = new ComponentPlan()
    .add(new ComponentSpec(ComponentType.GATE)
        .offset(0, 0, 10)
        .size(3, 1, 4));
```

## 最佳实践

1. **组件尺寸**：
   - 使用相对 Skeleton 原点的偏移
   - 尺寸单位是方块数

2. **标签使用**：
   - 使用语义标签（defensive, ceremonial, inner, outer 等）
   - 标签可以用于后续的样式选择

3. **参数传递**：
   - 使用 `params` Map 传递特殊参数
   - 例如：`component.param("ringRadius", 10)`

4. **错误处理**：
   - 如果组件没有对应的装配器，会被跳过
   - 建议在日志中记录缺失的装配器

## 调试技巧

1. **检查装配器注册**：
```java
boolean hasAssembler = ComponentAssemblerRegistry.has(ComponentType.ENCLOSING_WALL);
```

2. **单独测试组件**：
```java
ComponentAssembler assembler = ComponentAssemblerRegistry.get(ComponentType.ENCLOSING_WALL);
List<SemanticPlacementOp> ops = assembler.assemble(ctx, skeleton, component);
```

3. **检查语义操作**：
```java
List<SemanticPlacementOp> ops = ComponentAssemblyPipeline.assembleAll(ctx, skeleton, components);
// 检查 ops 的数量和内容
```

