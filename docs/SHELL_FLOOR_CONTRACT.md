# 箱体楼板与楼梯组合契约

更新：2026-10-03，第十四批。适用于 assembly SHELL_BOX / BOX_SHELL，不代表其他 MASS 或整栋生成器已采用同一楼板规则。

## 生成顺序

MetaAssemblyEngine 将 SHELL_BOX 委托给 AssemblyShellOps，复用现有材质与放置适配器。生成顺序为：逐层清除内部 → 外墙和窗带 → 实体楼板 → 屋顶。第十四批修复原来的最后内部清空删除中间楼板问题；扭转箱体也不再使用大包围盒删除刚生成的外墙和周边地形。

内部清空只覆盖局部内部样本，按各层 twistTurns 与 twistPhase 转换后输出空气；后续外墙和楼板覆盖离散旋转可能重合的位置。twistPhase 在 twistTurns=0 时也生效。逐格取整仍可能留下采样空隙，不能据此保证扭转外墙水密或地形已完全清除。

楼板位于局部 y=0、floorStep、2*floorStep……直到不超过 h；floorStep 默认 4，运行时限制 3..8。屋顶位于 h+1，楼板踏面方块顶部通常为 y+1。外墙范围 y=0..h。楼板只铺内部，不覆盖外墙。现有宽深算法仍取 -floor(w/2)..floor(w/2)，偶数尺寸实际会多一格；尺寸精确化尚未处理。默认窗带仍按四格循环，不会随 floorStep 自动对齐。

## 楼梯洞口与平台

MetaAssemblyCompiler 按 components 输入次序生成操作，随后输出 connections；MetaAssemblyEngine 按 ops 顺序执行。当前没有主体、洞口、楼梯的全局依赖排序。需要先生成主体和楼板，再生成 STAIR_SYSTEM；后者逐列写入踏面、支撑和请求的空气范围，切开已有楼板。后续任何覆盖操作都可能重新封住洞口或删除梯段。

例如以下局部组合连接 y=0 与 y=4 楼板，终点东侧 x=1 的既有楼板作为上层接续踏面。它没有自动创建独立平台或栏杆，也没有连接 y=8：

```json
{
  "components": [
    {"type": "SHELL_BOX", "w": 15, "d": 11, "h": 9, "floorStep": 4},
    {
      "type": "STAIR_SYSTEM",
      "from": {"x": -4, "y": 0, "z": 0},
      "to": {"x": 0, "y": 4, "z": 0},
      "width": 2, "clearHeight": 3, "carve": true, "support": true
    }
  ]
}
```

carve=false 不会挖楼板洞口，clearHeight 太小可能留下碰撞障碍；平台也必须落在实际楼板范围内。转弯、多梯段互相切空、跨越扭转楼层都需要单独规划，不能通过单个 from/to 自动推断。

## 验证边界

AssemblyShellOpsTest 的 11 个执行案例验证直体/正负扭转的楼板和屋顶保留、外墙保留、外部位置不清除、单独相位旋转，以及真实组件编译结果的四向主体与楼梯组合。组合测试调用生产几何函数和 PlacementUtil，采用可控材质适配器与最终操作快照；没有启动 ServerWorld，也没有运行生产执行器整个入口。

下一步需要统一楼层面、洞口、平台和梯段的几何身份，检查其他主体生成器及覆盖次序，并在游戏中验证碰撞、邻居更新、洞口护栏和真实通行。相关边界见 [楼梯契约](STAIR_CIRCULATION_CONTRACT.md)。
