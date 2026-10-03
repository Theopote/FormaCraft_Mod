# 地标与建筑类型路由契约

状态：维护中；核查：2026-10-03（第五批）。本文描述当前 Java components 路由与提示，不替代 Python Building Research 或所有兼容入口。

## 三种目录语义

- 固定模块：archetypes 中具有 generatorId，且不是 researchOnly、未列入 typology migrationMap；可通过 MODULE + landmark:id 引用，例如 pantheon、jiangnan_water_town。
- 已迁移建筑类型：旧地标 ID 在 structural_typologies_v1.json 的 migrationMap 中关联参数化类型，例如 birds_nest_stadium → stadium_bowl、gothic_cathedral → gothic_cathedral_hall。旧 ID 继续兼容解析，但不属于可用固定模块目录。
- researchOnly：识别真实建筑名称，供研究与组合规划使用，没有对应精确 MODULE；不能把圣家族大教堂等建筑静默解释为通用哥特模板。

目录数量不构成能力契约。测试应检查有效模块、迁移排除、研究条目排除和 ID 唯一性；禁止用旧的“至少 25 个模块”恢复已移除路线。

## 提示与计划

LandmarkRoutingPolicy 的 MANDATORY 是历史枚举名，表示指名候选强度，当前最终提示仍允许 Building Research 覆盖。它不代表无条件强制 MODULE。

已迁移候选的最终提示输出 STRUCTURE + typology:id 和 typology_id。明确点名鸟巢时保留 reference_landmark=birds_nest_stadium 作为比例参考；泛椭圆体育场只推荐 stadium_bowl，不自动添加指名参考。拒绝地标、原创意图继续遵守原有路由优先级。

reference_landmark 表示参考关系，不是固定模块执行指令。曲线、轮廓、结构参数、尺寸和 distinguishing_features 仍需经研究与编译验证，建筑类型标签本身不证明复刻准确。

## 兼容与指标

TypologyComponentRouter.extractTypologyId 能从 typology 特征、类型参数和旧 landmark/module ID 迁移得到类型。TypologyRoutingMetrics 不将这种迁移条目计为旧 structure-generator hint；真正保留的固定模块仍按模块识别。

LandmarkRoutingMetrics 只判断有效 MODULE 的精确命中。已迁移的 gothic_cathedral 不再算精确模块匹配；这不等同于否认 gothic_cathedral_hall 的类型能力。

defaultParams 是 JSON 通用数值，消费者必须接受 Number，不能依赖 Gson 将整数保存为 Integer；例如 levels=13 的语义保持 13，即使底层类型是 Double。

## 验证范围与下一步

本轮验证 Java 提示输出、目录过滤、迁移识别和指标分类。资源中的知识卡、Python 研究规划和兼容 BuildingSpec 仍有独立的提示/路由来源，需后续统一；不能只凭一个关键词或一个 Java 候选宣称完整路由一致。

下一步应建立共同的路由决策结果，区分候选、研究确认、兼容迁移和最终执行能力，减少各入口重复匹配；先补跨路径契约案例，再迁移实现。
