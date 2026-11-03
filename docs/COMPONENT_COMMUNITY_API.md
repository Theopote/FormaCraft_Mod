# 远端索引与下载契约草案 v1

状态：设计提案，尚无部署端点。请求解析、离线索引与显式只读 HTTP 搜索客户端已实现，摘要响应仅支持 [offline-summary.v1 子集](COMPONENT_COMMUNITY_TRANSPORT.md)。以下完整字段和其他端点仍为拟议契约；不是当前游戏网络 payload 或 Python 后端的已有能力。示例位于 [community-examples](community-examples/README.md)，完整设计示例不冒充当前子集响应。

发布详情客户端支持 offline-release.v1，完整包 GET 及持久收据也已有实现；具体字段与边界见 [下载文档](COMPONENT_COMMUNITY_DOWNLOADS.md)。尚无实际托管服务，完整领域版本/账户/发现修订模型仍是提案。

客户端另有 [Range 分块续传 API](COMPONENT_COMMUNITY_RESUME.md)，严格区分 200 完整下载与 206 区间数据；分块暂存不授予安装权限。

[玩家任务协调](COMPONENT_COMMUNITY_TASKS.md) 已接入服务端显式配置与生命周期清理，[社区 C2S/S2C 消息](COMPONENT_COMMUNITY_GAME_PROTOCOL.md) 与客户端状态机也已注册。[社区侧栏](COMPONENT_COMMUNITY_UI.md) 提供搜索、候选选择及缓存下载；[可信配置](COMPONENT_COMMUNITY_CONFIGURATION.md) 提供存档配置与管理员命令，[入库确认](COMPONENT_COMMUNITY_INSTALL.md) 与[所选来源配方收藏](COMPONENT_COMMUNITY_RECIPES.md)已接入，配置图形界面尚未实现，没有开放自动安装。

## 第一个可实现范围

先实现只读能力说明、搜索、发布详情、相同配置的来源列表和按整包指纹下载。测试环境预置经过审核的包；首版没有登录发布、自动更新、训练数据下载、任意 URL 代理或远端脚本执行。单一 authorityId 映射到服务器配置的服务地址；来源消息不能提供任意主机来改变目标。

读接口不依赖社区账户。未来账户及发布授权另行接入，不能把 Minecraft UUID、游戏 OP 权限或包内 namespace 当作社区账户证明。多人游戏由游戏服务器负责目标环境检查与安装；LLM 后端只接收该任务所需的结构化摘要。

## 接口清单

| 方法与路径 | 请求重点 | 返回与边界 |
| --- | --- | --- |
| GET /v1/capabilities | 无私人信息 | 契约版本、支持的包/指纹版本、大小/分页限制、已实现能力 |
| POST /v1/search | 单个 need、环境声明、字段集合、pageSize、cursor | 稳定页快照、配置组摘要、每组一个明确来源候选、证据/未知项 |
| GET /v1/resources/{resourceId}/releases | cursor/pageSize | 可见版本列表；版本标签与推荐指针分别返回 |
| GET /v1/releases/{releaseId} | 明确 releaseId | 不可变发布记录、当前可交付状态和发现修订 |
| GET /v1/content-groups/{fingerprint}/sources | cursor/pageSize、原查询约束 | 同配置的可见来源；逐份保留许可、类型/标签及发布主体 |
| GET /v1/package-blobs/{packageSha256} | 原包整体指纹，下载所选 release 的交付令牌 | 完整 `.fccomponent.json` 原始字节，不返回重新组装的包 |

完整包地址明确使用 packageSha256，替代早期 /blobs/{sha256} 中未区分载荷与整包的写法。预览媒体将使用独立对象类型与指纹，不能把图片 hash 当包身份。发布/上传接口保留在领域设计中，后续阶段再确定其认证和提交协议。

## 搜索输入

每个请求包含 contract、requestId、needId、query、environment、fields、pageSize 和 cursor。query 表达 exactTypeKey、role、keywords、softTags、hardTags、targetSize、attachment、visualIntent、suitePreference 和失败策略；字段不是直接透传给当前 ComponentQuery 的 JSON。搜索请求解析与默认值已实现，详见 [离线原型](COMPONENT_COMMUNITY_OFFLINE.md)；可解析条件与当前模拟索引可执行条件分别检查。

targetSize 带 frame=component_xyz 和 unit=block，x/y/z 为正整数或 null；null 表示未指定，不能把 0 当未指定。sizePolicy 区分 fixed 与 negotiable，后者的调整建议须由建筑规划器接受。开洞与净空独立表达；首版未支持的约束返回 unsupported_constraint，不静默忽略。

environment 使用目标 Minecraft 版本和 required/installed mod 的有界清单，不接收本机路径、账户令牌或完整服务器配置。服务器可以仅提交需求相关模组；不完整声明导致兼容性 unknown，不能直接标 compatible。没有目标环境时仍可浏览，但不得声称可安装。

pageSize 默认 10、范围 1–20；请求 UTF-8 正文上限 32 KiB，关键词上限 256 个 Unicode 标量值，标签总数上限 64，单个标签上限 128，环境模组声明上限 128。不接受客户端自定义排序脚本或无限候选数。字段名/值不支持时明确报错，避免硬条件悄悄变为软偏好。

## 搜索输出与游标

响应包含 requestId、needId、queryFingerprint、indexSnapshot、rankingPolicy、items、nextCursor 和 truncatedFields。items 是按已核对配置分组后的卡片；每张卡片只携带一个符合原查询的 preferredCandidate 和其他匹配来源数量，展开来源用单独分页接口。每个候选带确定 releaseRef（authorityId/resourceId/releaseId/整包及原包双指纹），而不是返回一个随时变化的推荐版本别名。

preferredCandidate 包含展示字段、包声明摘要、原尺寸、作者变化范围、接口摘要、requiredCapabilities、验证等级、兼容性状态，以及分项排序证据。metadata_fit=possible 只是摘要上的可能性，actual_fit=unknown 表示还未下载编译。搜索中不下发 blocks、完整 componentJson、完整规则数组或无限说明文本。

单个响应上限 128 KiB。description 可截断并在 truncatedFields 标记；ID、指纹、规则所需关键字段不截断。若仍无法满足预算，减小本页并提供 nextCursor；单个候选关键摘要本身超过上限则返回 summary_too_large，不能交付不完整的“已验证”候选。缓存键包含规范化查询、目标环境、可见性范围、字段集合、排序策略和索引快照。

游标是不透明服务端令牌，绑定查询指纹、可见性范围、快照水位和最后排序键，首版有效期建议 5 分钟。排序以分项策略结果和稳定 releaseId 打破同分。新发布不会插入同一快照的后续页，避免重复或漏项；权限和隔离状态仍实时重检。查询/环境/字段改变时旧游标返回 cursor_mismatch，过期返回 cursor_expired，客户端可重新搜索并按 releaseId 去掉已展示重复项。客户端不能自行解释游标或把 page=2 近似成同一快照。

不同排序策略或指纹算法不可共用旧游标。匿名/登录/私人范围之间也不共用缓存和游标；不可见来源按统一不可用状态处理，不通过分组来源数量披露私人记录。

## 发布详情与原包摘要

详情把 immutableRelease、currentStatus、discoveryRevision 分开。immutableRelease 至少包含 resourceId/releaseId、版本标签、发布序号、platformPublisher、sourceClaim、package 对象描述、结构/配置指纹、提取器版本和父引用。

sourceClaim 保存包内 namespace/component.id/manifest.version/author；platformPublisher 保存经过平台账户归属检查的发布主体，两者可以不同。releaseVersion 与 sourceClaim.version 也可以不同，不能要求既有捕获包为匹配平台版本而重写。详情明确显示这种差别，不隐去原声明。

包对象描述包含 packageSha256、byteLength、mediaType 和 packageSchema；原包摘要另含 payloadSha256、manifestSha256。摘要中的许可原文或明确声明状态、trainingPermission、依赖和作者变化权限必须绑定这份包。平台发现修订不能把 UNSPECIFIED 改成 ALLOWED，或把未知净空改成已验证安装。

currentStatus 决定当前是否可交付。来源在搜索后撤回/隔离或权限变化时，下载准备须重新确认；返回明确 release_unavailable，不自动替换为同配置组的另一来源。

## 有界下载、缓存与收据

选中候选后先重新取详情并固定其 releaseId、packageSha256、byteLength、payloadSha256、manifestSha256。下载通过配置的同源网关获取，不把账户令牌发给由说明文本指定的地址。首版不引入可绕过当前交付检查的第三方长期下载链接。

传输实现需支持按字节范围继续下载，校验服务返回的范围、总长度和对象身份；对象变了、范围不连续、返回长度超额时丢弃暂存并重取详情，不把两次传输拼成一个包。断点状态至少绑定 authorityId、releaseId、整包 hash、期望长度和已接收范围。客户端最多接收声明长度且不得超过当前 16 MiB 包上限；不能只信 Content-Length，接收过程中也计数。

当前交付状态在每次新下载/续传请求检查。隔离不能收回已经交付的字节或本地快照；不承诺撤回能删除用户已保存的内容。没有权限的旧交付令牌不能继续换包下载。

完整接收后依次检查：实际字节数 → 整包 SHA-256 → 严格 UTF-8/包 schema → 载荷 SHA-256 和 manifest SHA-256 → 作者规则/数据有效性 → 目标环境兼容性。失败的暂存文件不安装；服务端 validated 标签不能跳过任何本地检查。inspectBytes 结果仍不能代替目标环境验证后的可安装对象。

缓存将原始下载字节按 packageSha256 保留。当前 ComponentAssetSnapshots.writeTo 使用现有包序列化，不保证与下载的原始整包字节相同；因此不得用重序列化结果证明原下载 hash。后续需要 DownloadReceipt 与原始字节缓存：收据记录所选发布身份、各指纹、实际长度和验证状态，逻辑原包快照继续用 payload+manifest 双指纹。

被收藏/建筑锁引用的远端收据同时固定其原始下载对象，垃圾回收按引用可达性处理；只存逻辑快照而已删原始字节时，最多声称逻辑内容/授权快照完整，不能声称还能校验或原样导出完整下载对象。下载收据是接收记录，不是作者身份或权利认证。

## 游戏服务器与 LLM 的适配层

拟议 CommunityDiscoveryGateway 负责可信 authority 配置、任务预算、HTTP 查询/下载、取消及验证；拟议 NeedPlanningLoop 负责将规划需求交给网关，再把有限证据反馈给规划器。它们尚未实现，不能假定现在一次 LLM 返回完整建筑就已经能调用远端工具。

HTTP 摘要最多 128 KiB，而现有游戏请求/响应各有边界；游戏侧需要独立的有界分页/分块消息，不能直接塞进旧字符串包或把目录同步 payload 当社区接口。/models、/summarize 代理也不是任意服务地址的通用代理。

给 LLM 的证据再由 adapter 确定性压缩：每需求最多 5 个候选、每需求最多 8 KiB，总计最多 16 个需求；描述裁剪可见标记，不删除 ID/来源/未知项。完整许可、来源和变化规则供用户详情及本地验证使用，不需要全部塞进模型上下文。预算耗尽、用户取消、断线或新任务替代时停止后续请求，旧响应不能改当前任务。

采用候选之后须建立领域文档中的 SelectedReleaseBinding，并把所选来源 B 原包带入配方和建筑锁。这个来源绑定和锁收据接通前，远端 AI 功能保持只读/建议态；不绕过现有预览/确认写世界。

## 错误语义

| code | 客户端行为 |
| --- | --- |
| invalid_request / request_too_large | 拒绝损坏格式、类型混用、超限结构；修正请求后再发，不无限重试 |
| invalid_response / response_too_large / unsupported_encoding | 拒绝错配、损坏或超限响应，不交给安装器或规划器 |
| redirect_rejected / request_timeout / cancelled | 停止当前请求；不自动跟随新地址或恢复已取消任务 |
| range_not_supported / invalid_range / invalid_checkpoint | 停止续传，不把错位、损坏或完整响应拼接成区间数据 |
| download_busy / resume_capacity | 停止并发写入或超配额操作，保留已有有效块，不无限重试 |
| unsupported_contract / unsupported_capability | 停止该功能，保留本地能力，不猜测字段含义 |
| unsupported_constraint | 显示不支持的硬条件；由用户/规划器明确改需求 |
| cursor_expired / cursor_mismatch | 重新检索；不拼接成原快照 |
| release_unavailable | 停止该来源；不悄悄换来源或授权 |
| summary_too_large / package_too_large / budget_exceeded | 显式说明限制，不截断关键内容或无限重试 |
| download_incomplete / object_changed / package_hash_mismatch | 删除无效暂存，重新确认详情；不入库 |
| unsupported_package_schema / environment_mismatch | 拒绝安装，不自动安装/执行依赖模组 |
| adaptation_failed / installation_failed | 保留具体本地诊断；必要需求失败，可选需求可报告省略 |
| service_unavailable / rate_limited | 有界重试或离线降级，已有锁与库不变 |

错误响应包含 requestId、code、可展示 message、retryable 和可选 retryAfterSeconds，不暴露存储路径、数据库错误或其他用户私有 ID。协议错误、来源拒绝和实际适配失败分别统计，不能都报“没有候选”。

## 第一阶段验收与实现门槛

1. 用固定的合成包集合验证服务提取器与当前 Java 指纹算法一致；包含同结构不同配置、同配置不同名称/许可，以及属性/集合顺序变化。
2. 验证稳定分页、相同查询重放、快照过期、新发布插入、当前来源隔离与不可见来源计数。
3. 验证正常下载、断点恢复、服务忽略范围、长度撒谎、对象变化、损坏编码、整包/载荷/manifest 分别损坏。
4. 验证数据验证不等于环境验证，本地确认前无库写入，取消/断线无过期回包串入。
5. 验证本地 A 去重后仍能保留所选 B 的声明、标签证据、原包与锁收据；此项未通过，不开放远端 AI 自动安装/生成。

离线实现已开始，当前范围见 [社区离线原型](COMPONENT_COMMUNITY_OFFLINE.md)。内部模型、原始流校验和 mock 索引不等同于完整 JSON 契约、HTTP 下载器或账号接入；上述端点仍为设计提案。
