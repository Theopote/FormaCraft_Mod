# Formacraft 项目系统性检查与改进计划

## 项目架构概述

Formacraft 是一个 Minecraft 模组，通过 LLM 对话生成建筑。整体架构：
- **客户端（Java/Fabric）**：UI、聊天面板、预览系统
- **服务端（Java/Fabric）**：网络处理、建筑生成、执行队列
- **Python 后端（FastAPI）**：AI 规划、LLM 调用、规格生成

### 完整流程
```
玩家输入文本 → ChatPanel → FormaCraftNetworking.sendBuildRequest (C2S)
  → 服务端 BuildRequestHandler → OrchestratorClient.requestBuildingSpec
  → Python /build 端点 → ai_planner.generate_building_spec
  → 返回 BuildingSpec → 服务端发送 ResponseBuildSpecPayload (S2C)
  → 客户端预览 UI → 玩家确认 → ConfirmBuildPacket
  → BuildExecutionService.enqueueBuild → 分 Tick 执行建造
```

---

## 🔴 关键问题清单（P0 - 必须修复）

### 1. **配置管理系统不完整**
**问题**：
- `ConfigManager.loadConfig()` 只是 stub，实际没有加载任何配置
- Python 后端 URL 硬编码在多个位置（`localhost:8000`）
- LLM API Key 等敏感信息缺少统一配置入口

**位置**：
- `src/main/java/com/formacraft/common/config/ConfigManager.java`
- `src/main/java/com/formacraft/common/network/FormaCraftNetworking.java:56`
- `src/main/java/com/formacraft/server/networking/BuildRequestHandler.java:17`

**影响**：无法在不同环境配置不同后端地址，用户体验差

### 2. **错误处理不够健壮**
**问题**：
- Python 后端不可用时缺少清晰的错误提示
- LLM API 调用失败时的回退机制不明确
- 网络超时设置可能不合理（600秒对于用户太长）

**位置**：
- `python_backend/app/routes/build.py` - 异常处理
- `src/main/java/com/formacraft/server/orchestrator/OrchestratorClient.java` - HTTP 错误处理
- `src/main/java/com/formacraft/common/network/FormaCraftNetworking.java` - 超时设置

**影响**：后端故障时用户不知道发生了什么

### 3. **编译错误风险**
**问题**：
- 最新的崩溃报告显示 `NoClassDefFoundError: HouseGenerator$1`（匿名内部类问题）
- 可能存在其他匿名内部类的编译问题

**位置**：
- `src/main/java/com/formacraft/common/generation/structure/HouseGenerator.java:2882`
- 其他生成器可能存在类似问题

**影响**：游戏可能崩溃

### 4. **前后端数据格式对齐验证不足**
**问题**：
- Java 和 Python 端的模型定义可能不同步
- 缺少自动化测试验证数据格式一致性

**位置**：
- `python_backend/app/models/` vs `src/main/java/com/formacraft/common/model/`
- `python_backend/tools/spec_contract_smoke.py` 只测试部分字段

**影响**：可能因字段不匹配导致运行时错误

---

## 🟡 中等问题清单（P1 - 建议修复）

### 5. **Python 后端自动启动不够可靠**
**问题**：
- `BackendAutoStarter` 可能在某些环境下无法正确检测 Python
- 缺少对虚拟环境的支持

**位置**：
- `src/main/java/com/formacraft/client/backend/BackendAutoStarter.java`

**影响**：用户需要手动启动后端，体验不佳

### 6. **建筑生成质量验证不足**
**问题**：
- 缺少对生成建筑尺寸的合理性检查
- 没有验证建筑是否超出边界
- 缺少材料一致性的自动检查

**位置**：
- `src/main/java/com/formacraft/common/generation/structure/router/GeneratorRouter.java`
- 生成器输出验证

**影响**：可能生成不符合预期的建筑

### 7. **预览系统可能不完整**
**问题**：
- 预览和实际建造之间可能存在不一致
- 轮廓渲染可能在某些情况下不显示

**位置**：
- `src/main/java/com/formacraft/client/preview/OutlineRenderer.java`
- `src/main/java/com/formacraft/server/preview/PreviewStorage.java`

**影响**：用户预览的建筑和实际生成的不一致

### 8. **LLM Prompt 优化空间**
**问题**：
- 根据 `CAPABILITIES_AND_GAPS.md`，布局语义（对称/轴线）表达能力不足
- 功能分区（前店后宅）支持不完整

**位置**：
- `python_backend/app/services/ai_planner.py`
- Prompt 模板

**影响**：AI 生成的建筑可能不符合用户描述

---

## 🟢 次要问题清单（P2 - 可选优化）

### 9. **文档不完整**
**问题**：
- 缺少用户使用指南
- API 文档不够详细
- 开发环境搭建文档可能过时

### 10. **性能优化空间**
**问题**：
- 建筑生成可能阻塞主线程
- 没有缓存机制避免重复计算

### 11. **日志系统可改进**
**问题**：
- 日志级别不统一
- 缺少结构化日志输出

---

## ✅ 已完成的优秀功能

1. **完整的网络通信系统**：C2S/S2C 数据包体系完善
2. **Forma-Gene Protocol 集成**：参数化建模、语义材质、规则化立面
3. **建筑执行队列**：分 Tick 执行避免卡顿
4. **Undo 系统**：支持撤销建造操作
5. **预览系统**：建造前可以预览
6. **Assembly 系统**：支持复杂的建筑组件定义

---

## 📋 改进计划（优先级排序）

### 第一阶段：修复关键问题（P0）

1. **完善配置管理系统**
   - [ ] 实现 `ConfigManager` 完整功能
   - [ ] 添加配置文件读取（JSON/YAML）
   - [ ] 统一后端 URL 配置
   - [ ] 添加 LLM 配置界面

2. **改进错误处理**
   - [ ] 添加后端健康检查
   - [ ] 改进错误消息显示
   - [ ] 添加超时配置选项
   - [ ] 改进回退机制

3. **修复编译问题**
   - [ ] 检查并修复 `HouseGenerator` 的匿名内部类问题
   - [ ] 搜索所有生成器中的匿名内部类
   - [ ] 添加编译测试

4. **加强数据格式验证**
   - [ ] 扩展 `spec_contract_smoke.py` 覆盖更多字段
   - [ ] 添加运行时数据格式验证
   - [ ] 添加版本兼容性检查

### 第二阶段：改进用户体验（P1）

5. **优化后端自动启动**
   - [ ] 改进 Python 检测逻辑
   - [ ] 支持虚拟环境
   - [ ] 添加更多错误诊断信息

6. **增强生成质量检查**
   - [ ] 添加尺寸合理性验证
   - [ ] 添加边界检查
   - [ ] 添加材料一致性检查

7. **改进预览系统**
   - [ ] 确保预览和建造一致性
   - [ ] 改进轮廓渲染
   - [ ] 添加更多预览选项

8. **优化 LLM Prompt**
   - [ ] 添加布局语义支持
   - [ ] 添加功能分区支持
   - [ ] 改进风格描述

### 第三阶段：优化和文档（P2）

9. **完善文档**
   - [ ] 编写用户使用指南
   - [ ] 完善 API 文档
   - [ ] 更新开发环境搭建文档

10. **性能优化**
    - [ ] 优化建筑生成性能
    - [ ] 添加缓存机制
    - [ ] 优化网络请求

11. **改进日志系统**
    - [ ] 统一日志级别
    - [ ] 添加结构化日志
    - [ ] 改进日志输出格式

---

## 🚀 修复进度

### ✅ 已完成
1. **配置管理系统** ✅
   - 实现了 `ConfigManager` 完整功能
   - 修复了多处硬编码的后端 URL
   - 统一使用配置系统读取后端地址
   - 修改文件：
     - `src/main/java/com/formacraft/common/config/ConfigManager.java` - 完善配置加载逻辑
     - `src/main/java/com/formacraft/common/network/FormaCraftNetworking.java` - 从配置读取后端地址
     - `src/main/java/com/formacraft/server/networking/BuildRequestHandler.java` - 从配置读取后端地址
     - `src/main/java/com/formacraft/FormacraftMod.java` - 确保配置在模组初始化时加载

### 🔄 进行中
2. **错误处理改进**
   - 需要添加后端健康检查
   - 改进错误消息显示
   - 添加超时配置选项

### 📋 待修复
3. 编译问题修复
4. 数据格式验证
5. 其他 P1/P2 问题

 