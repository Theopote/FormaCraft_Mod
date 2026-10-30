# 构件套系偏好

## 资产声明

在现有 tags 中填写 `suite:作者命名空间/套系名`，例如 `suite:alice/courtyard`。门、窗、栏杆可以属于同一套系；同一资产允许多个套系标签。捕获页已有标签输入，无需新增结构字段或迁移旧库。

套系身份按现有完整标签归一规则比较，保留冒号和斜杠边界，不根据名称子串、风格或材料自动推断套系。作者命名空间是约定，不是经过认证的作者身份。

## 查询与选择

```json
{
  "selection": {
    "preferredSuite": "alice/courtyard",
    "suiteBonus": 0.05,
    "adaptationPenalty": 0.10
  }
}
```

这是偏好而非必须条件：只对通过受控适配的候选加分，最终分数为既有适配分加套系加分。suiteBonus 默认 0.05，限定在 0–1，非有限值恢复默认。匹配证据写入候选 fitScore。缺少偏好时行为不变，缺少同套系可用件时仍可选择其他候选。

若必须限定套系，使用 `constraints.mustHave: ["suite:alice/courtyard"]`；不适配仍然失败，不能扩大变化权限。套系不会证明形态、尺寸或真实世界安装可行，也不会自动触发换材。

支持显式查询偏好；对于已有库查询，实时编译阶段也会按建筑分组协调套系，详见 COMPONENT_BATCH_SELECTION.md。LLM 自动决定哪些原生部件改用库、任意多套系联合优化、远端发布与套系浏览筛选尚未实现。

## 开发验证

运行 scripts/verify-component-assets.ps1 -Offline。脚本在 .gradle/component-asset-verification 中创建临时 Gradle 项目，读取仓库当前源码与资源，测试工作目录仍为仓库根目录。其项目级 Loom 缓存和编译输出独立，不删除 IDE 正在使用的原项目缓存。Gradle 全局依赖缓存仍复用；首轮可不带 -Offline 获取缺少的依赖。测试报告位于该临时项目的 build/reports/tests/test/index.html。
验证结果：上述独立验证脚本运行成功，45 项相关测试全部通过；套系自动规划与游戏内体验尚未验收。
