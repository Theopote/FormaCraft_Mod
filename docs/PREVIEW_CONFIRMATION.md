# 预览确认与实际建造

## 2026-10-04 游戏日志故障

13:33 至 13:39 多次预览成功，但点击确定后返回「未知或不完整的命令」，错误位置为 forma_confirm 或 forma_confirm force。没有执行确认命令，也没有进入建造队列。这批现象不是建筑在其他坐标放置；LlmPlanMetrics.success 只表示预览生成成功，不能作为世界方块已放置的证据。

FormaCraftCommands 原先用 JVM 静态布尔值去重。单机退出、切换或重建服务器时新 CommandDispatcher 会再次触发注册回调，但布尔值仍为 true，导致新世界无命令。现在使用弱引用集合按 dispatcher 去重：同一个 dispatcher 的重复回调跳过，新 dispatcher 始终注册。回归测试验证两个 dispatcher 都有 forma_confirm、force 子命令和 forma_cancel。

确认入队后记录 `Preview confirmed: dimension=... origin=... blocks=... force=...`。这证明已进入建造队列；最终放置仍应核对 BuildExecutionService 的完成反馈。预览与确认共用 PreviewStorage 中的 GeneratedStructure，不重新计算建筑坐标。

## 最后一次双栋请求

初次请求被旧聊天中的编辑词影响，误触发 no_build_intent。意图判断现在优先认可当前明确的建造请求，历史编辑词不能否定它。

补充中式风格后，13:42 的装配 OPENINGS 只有 side，没有执行协议要求的 face/faces，Java 报 E_FACE_MISSING，未生成预览。后端现在检查同样的字段，让现有修复循环在返回游戏前发现问题。原响应脱敏保存在 linked-buildings-missing-face.json；不猜测方向或关闭 Java 校验，因此仍需重新生成验收双栋建筑。

## 游戏复测

重启游戏和后端加载修复。创建预览，确认后应收到开始建造反馈与上述入队日志；退出世界再进入后重复确认，命令应继续可用。质量报告含 Error 时原有二次强制确认流程继续生效。模组 jar 位于 build/libs/formacraft-1.0.0.jar。
