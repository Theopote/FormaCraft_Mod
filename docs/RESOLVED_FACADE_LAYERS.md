# 退台逐层轮廓与顶层覆盖（第十一批）

日期：2026-10-04。

第十二批已推进 [矩形主体屋顶接合框架](STEPPED_ROOF_ATTACHMENT.md)，这类屋顶不再使用底层尺寸；本文的未收缩说明是第十一批交付时的范围，异形和附楼仍保留原有布局。

`ResolvedFacadeLayers` 是主体生成和屋顶宿主验收共享的逐 y 层矩形解析。复用 ProportionalFacadeCalculator，提供每层 width/depth/xOffset/zOffset；实际体块轮廓仍与原有 ComponentFootprintMask 相交，不把缩进矩形当作完整实体。

启用条件保持原生成器规则：features 含 stepped/setback/进退/立面/tiered 等原有关键词，或 genome.form.progression 为 stepping/tapering。floor_height/floorHeight 和旧 feature 层高规则继续通过 ResolvedComponentGeometry 解析；setback_ratio/setbackRatio 支持数字字符串，优先于 genome 默认比例，再使用 feature 比例。仅有比例参数不会额外启用退台，零比例仍沿用原计算器的自动比例语义。本批没有更改这些兼容性规则。

MASS_MAIN 的内部掏空现在同时满足当前层矩形内部和原形状内部条件。原实现仅按底层尺寸判断，会把退台后的新外墙误当作内部删去；修正后保留缩进层墙体，室内仍掏空。表面来源记录也包含缩进层边缘。

平屋顶宿主覆盖不再跳过退台，逐体块使用实际顶层矩形与形状掩码相交。判断高体块遮挡低体块时，使用高体块在对应 y 层的矩形，而非整个底层包围范围。

本批未强制缩小屋顶。现有推断屋顶与卫星对齐仍可能使用底层尺寸，完整的大屋顶覆盖缩小顶层时可以通过覆盖检查；是否应收缩属于屋顶布局及外挑设计，不能用缺失验收自动改写。后续需要为推断屋顶建立顶层接合框架，同时保留用户显式宽檐意图。退台露台封板、逐层窗户布置、墙体与屋面的接触面积和坡顶接缝仍待实现。

测试使用实际主体生成结果验证缩进墙体存在、室内保持空心，并验证按顶层尺寸铺设的平屋顶通过、顶面删一格会失败；另验证旧格式比例启用规则和大写特征兼容。

验证：`gradlew.bat build --offline` 成功，Java 538 项测试通过；架构检查 20 项已知债务、0 新增。已有源码 jar 重映射警告仍存在，运行模组 jar 构建成功。本批未修改 Python 后端。
