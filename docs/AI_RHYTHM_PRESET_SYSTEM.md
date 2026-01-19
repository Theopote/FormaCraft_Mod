# AI 节奏预设系统（AI Rhythm Preset System）

## 🎯 核心设计理念

**AI 在 Formacraft 里不"设计立面"，而是"选择节奏策略"。**

### 三权分立架构

```
AI = 策略选择者
系统 = 规则执行者
玩家 = 最终裁决者
```

## 📋 系统架构

### 1. FacadeRhythmPresetLibrary（预设库）

**位置：** `src/main/java/com/formacraft/common/mass/rhythm/FacadeRhythmPresetLibrary.java`

**核心职责：**
- 提供一组已验证的节奏预设
- AI 只能从这些预设中选择，不能生成新的

**预设类型：**
- `RESIDENTIAL_REGULAR` - 住宅常规节奏
- `RESIDENTIAL_GROUPED` - 住宅分组节奏
- `PALACE_HIERARCHICAL` - 宫殿层次节奏
- `TEMPLE_AXIAL` - 寺庙轴向节奏
- `INDUSTRIAL_GRID` - 工业网格节奏

### 2. FacadeRhythmPreset（预设定义）

**位置：** `src/main/java/com/formacraft/common/mass/rhythm/FacadeRhythmPreset.java`

**核心字段：**
- `id` - 预设 ID
- `profile` - 节奏配置（已验证的规则组合）
- `styleTags` - 风格标签（匹配哪些建筑风格）
- `buildingTags` - 建筑类型标签（匹配哪些建筑类型）
- `minFloors` / `maxFloors` - 适用层数范围
- `tuningRange` - 允许的参数微调范围

### 3. FacadeRhythmPresetSelector（AI 选择器）

**位置：** `src/main/java/com/formacraft/common/mass/rhythm/FacadeRhythmPresetSelector.java`

**AI 的输入（高度语义层）：**
```java
{
  "buildingType": "residential",
  "style": "traditional_chinese",
  "floors": 2,
  "massing": ["primary", "wing"],
  "symmetry": "strong",
  "expression": "calm"
}
```

**AI 的任务（只做 3 件事）：**
1. 选择一个或两个 FacadeRhythmPreset
2. 在允许范围内微调参数（可选）
3. 为不同立面选择不同节奏（进阶但安全）

**AI 的输出：**
```java
{
  "primaryPresetId": "TEMPLE_AXIAL",
  "tuning": {
    "spacing": 3
  },
  "facadeOverrides": {
    "FRONT": "PALACE_HIERARCHICAL",
    "SIDE": "RESIDENTIAL_REGULAR"
  }
}
```

### 4. FacadeRhythmPresetValidator（校验器）

**位置：** `src/main/java/com/formacraft/common/mass/rhythm/FacadeRhythmPresetValidator.java`

**校验规则：**
- 单层建筑 ❌ TEMPLE_AXIAL（需要多层才有效果）
- 工业建筑 ❌ PALACE_HIERARCHICAL（风格不匹配）
- 微调参数必须在允许范围内

### 5. FacadeRhythmPresetResolver（解析器）

**位置：** `src/main/java/com/formacraft/common/mass/rhythm/FacadeRhythmPresetResolver.java`

**核心职责：**
- 将 AI 选择的预设和微调参数转换为最终的 FacadeRhythmProfile
- 校验预设兼容性
- 应用微调参数

## 🔄 完整流程

```
AI 理解建筑语义
   ↓
AI 选择节奏预设（从预设库）
   ↓
系统校验 / 修正（Validator）
   ↓
解析为 FacadeRhythmProfile（Resolver）
   ↓
FacadeRhythm 应用到 Socket
   ↓
Component 装配
```

## ✅ 关键优势

1. **AI 不会生成"怪物建筑"**
   - 所有预设都是已验证的规则组合
   - 微调参数有严格的范围限制

2. **AI 在它擅长的领域工作**
   - AI 擅长风格判断和语义理解
   - 不需要 AI 理解底层几何和规则

3. **系统控制所有危险参数**
   - 预设库由工程师/设计者维护
   - 校验器确保不会出现不合理的组合

4. **玩家可以 override**
   - 玩家可以选择不同的预设
   - 玩家可以调整微调参数

5. **调试非常容易**
   - 预设是离散的、可枚举的
   - 问题可以快速定位到特定预设

## 🎯 为什么这是 Formacraft 的"AI 甜蜜点"

✔ AI 擅长风格判断  
✔ 系统控制所有危险参数  
✔ 玩家可以 override  
✔ 调试非常容易  
✔ 不可能生成"怪物建筑"

---

**设计时间**: 2026-01-14  
**状态**: ✅ 已集成到 BuildingMassPipeline
