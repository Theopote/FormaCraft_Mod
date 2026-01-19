# 立面节奏系统（Facade Rhythm System）v1

## 🎯 核心定位

这一步正是把"规则生成"提升到"建筑感生成"的关键层。

**一句话总定义：**

立面节奏系统 = 决定"哪些 Socket 应该出现、对齐、重复、跳过"的规则层。

它不负责：
- 门窗长什么样
- 用什么方块

它只负责：
- 出现在哪里
- 出现的频率
- 是否对齐 / 对称 / 变化

## 📋 立面节奏系统在整体架构中的位置

```
BuildingMass
  → Skeleton
    → Socket（候选）
      → FacadeRhythm（筛选 / 对齐 / 分组）← 这里
        → Final Socket
          → Component Placement
```

👉 它是 Socket 的"审美过滤器"。

## 🔧 立面节奏的输入

FacadeRhythm 系统的输入不是 Mass，而是：

- **Exterior Skeleton**
- **某一 FloorLayer**
- **某一朝向（N / S / E / W）**

也就是说：

节奏是"按立面、按楼层、按方向"计算的

## 🔄 立面节奏的执行流程（v1 标准）

### Step 1：收集候选 Socket

```java
List<Socket> candidates = socketsOnSkeletonAndLayer(skeleton, layer);
```

### Step 2：按立面方向排序（非常重要）

左 → 右 或 前 → 后

### Step 3：应用 RhythmMode（节奏筛选）

**REGULAR（最常用）**
```
keep every N-th socket;
```

**GROUPED（示例：2-1-2）**
```
[窗][窗]  空  [窗][窗]
```

**HIERARCHICAL**
```
中央密
边缘疏
```

### Step 4：应用 AlignmentMode（对齐）

**AXIS_ALIGNED**
```
找 Primary Axis 在该立面上的投影
最近的 Socket 对齐到轴线
snap socket.x to axis.x;
```

### Step 5：应用 SymmetryMode（对称）

**BILATERAL**
```
找立面中线
镜像左右 Socket
mirror sockets around centerLine;
```

⚠️ 只对 Window / Balcony 生效，不对 Door

### Step 6：应用 VariationMode（防"太工整"）

**SMALL_SHIFT**
```
socket.x += random(-1, +1);
```

**SKIP_RANDOM**
```
if (random < 0.1) remove socket;
```

## 📊 门 / 窗 / 阳台的节奏差异

### Door 的节奏规则（极简）

- 不参与 Rhythm
- 只受 Axis、Entrance rule 影响

👉 门是"事件"，不是"节奏元素"

### Window 的节奏规则（核心）

- 参与全部节奏规则
- 是 Rhythm 的主要对象

### Balcony 的节奏规则（受限）

- 不参与对称
- 只参与 REGULAR / GROUPED
- 数量严格限制：`max balconies per facade = 1~2`

## 🔄 多层之间的节奏"垂直关联"

### 垂直对齐（v1 默认）

```
windows on layer N → inherit x positions from layer N-1
```

### 顶层变化

```
if (layer.role == TOP) {
    reduce window count;
}
```

### Ground 层破节奏（非常重要）

Ground 层：
- 不强制对齐上层
- 允许更自由

👉 这是现实建筑的常态

## 🤖 FacadeRhythm 与 AI 的关系（非常友好）

LLM 非常擅长输出这种信息：

```json
{
  "facadeRhythm": {
    "mode": "REGULAR",
    "spacing": 3,
    "symmetry": "BILATERAL",
    "variation": "SMALL_SHIFT"
  }
}
```

你再把它：
- 限制
- 校验
- 应用

AI 几乎不可能生成"离谱立面"。

## 🎨 Debug Overlay（必须）

### 显示内容

- 被保留的 Socket：实线
- 被节奏过滤的 Socket：虚线 / 灰
- 对称轴线：红线

你会一眼看到：
- 节奏是否均匀
- 是否"太密 / 太稀"
- 有没有破坏轴线

## 🏆 为什么这个系统非常"Minecraft + Formacraft"

- ✅ 不生成任何几何
- ✅ 只处理 Socket 列表
- ✅ 可逐层、逐面运行
- ✅ 可被玩家 override
- ✅ 非常容易调参

## 📚 你现在已经具备的能力跃迁

你已经从：

**"在墙上随机开窗"**

进化到：

**"按建筑逻辑生成立面节奏"**

这是真正的建筑感来源之一。

---

**实现时间**: 2026-01-14  
**状态**: ✅ 立面节奏系统完成，支持节奏筛选、对齐、对称、变化和层间关联
