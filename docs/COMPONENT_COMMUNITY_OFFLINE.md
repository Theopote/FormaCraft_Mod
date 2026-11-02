# 社区离线原型

状态：只读原型，包含 v1 搜索请求解析、明确的摘要响应 profile，以及显式调用的 HTTP 搜索客户端；不是社区托管服务或完整发现系统。实现位于 `common.component.community`；不注册游戏命令、消息或自动启动任务，不写入构件库或世界。响应与传输范围见 [摘要响应与 HTTP 客户端](COMPONENT_COMMUNITY_TRANSPORT.md)。

发布详情、完整包传输及独立持久缓存也已实现为显式 API，见 [下载与收据](COMPONENT_COMMUNITY_DOWNLOADS.md)。离线索引提供详情及原字节测试接口；未开启远端游戏功能。

## 已实现

`CommunityPackages` 定义不可变发布引用和包描述，验证 authority/resource/release 标识、整包/载荷/manifest 哈希格式、包 schema、长度及指纹算法版本。`inspect` 核对完整原始字节、原包双指纹和当前 Java 配置/结构指纹，只返回完整性检查结果；它不能授权安装。

`receive` 接收调用者提供的流，最多读取声明长度加一个探测字节，不关闭调用者的流。长度与完整性通过后执行现有目标环境校验，成功才返回 `Download`。其原始字节和现有逻辑包分别保存，原始字节的输入/输出均作防御复制；`Verified.toBytes()` 不代替原始下载对象。`snapshotKey` 仍使用 payload+manifest 双指纹。新增独立缓存负责持久化收据，尚未将收据固定到配方/建筑锁。

`OfflineCommunityIndex` 是预置测试包的数据源，单实例只属于一个 authority。添加包时检查完整性，发布 ID 永不覆盖；没有身份认证或公开发布入口。搜索按精确类型 key 和精确硬标签筛选来源，然后按当前指纹加规范化精确比较分组。同配置不同来源分别保留包、原声明与许可；摘要的 actualFit 始终为 unknown，完整性校验不等于适配成功。

分页以 releaseId 确定顺序，游标绑定 Query、pageSize、分组快照和五分钟有效期。新发布不进入已有快照；每次分页重新检查来源可用性、重算匹配来源数，空组跳过。下载再次检查所选发布可用性与完整 releaseRef，不自动替换来源。可以重放未过期游标，但不是跨进程持久令牌。

内存限制：256 个发布、原包总计 32 MiB、128 个有效游标、单页 1–20 组、每个原包不超过现有 16 MiB。满额明确失败，不自动淘汰已有发布或延长分页快照。索引只支持公开预置数据；撤回模拟可用性，不能证明存在私人权限体系。

## 使用边界

1. 测试代码从现有 Java 包生成器生成真实包及摘要，加入索引；文档中的重复数字 hash 示例不能冒充真实包导入。
2. 调用 `search(Query, pageSize, cursor)` 浏览，或按完整 `ReleaseRef` 下载。
3. `Download.verified()` 可作为后续安装事务的输入，但当前原型不调用该事务，也不建立 SelectedReleaseBinding。

索引内部 Query 仅包含 exactTypeKey 和 hardTags；外层搜索请求解析器保留完整已定义条件，桥接前检查能力。role、尺寸、净空、环境摘要、软标签、套系、字段投影和排序证据的执行仍需后续实现；不能把请求解析成功称为全部检索能力已支持。索引搜索不判断兼容性；目标环境检查发生在下载校验阶段。

## JSON 请求适配范围

`CommunitySearchContract`：搜索请求 UTF-8 上限 32 KiB，JSON 嵌套最多 16 层、最多 4096 个值；重复属性、未知属性、损坏编码、孤立代理字符、注释、BOM、尾随内容与类型混用均拒绝。请求根字段 contract/requestId/needId/query 必需，pageSize 默认 10；fields 缺省为 identity/package/type/rights/evidence，cursor/environment 可省略或为 null。环境存在时 minecraftVersion/installedMods/completeness 必需，completeness 为 complete 或 partial。

installedMods 只列依赖模组，不重复 minecraft 键；Minecraft 版本使用 minecraftVersion。模组键为 1–64 个小写 ASCII 字母/数字/下划线/连字符，首字符为字母，版本为最长 128 个 Unicode 标量值的非空文本，最多 128 项。

query 的所有检索条件均可省略；targetSize 存在时 frame/unit/x/y/z 必需，坐标为正整数或 null。sizePolicy 默认 fixed；required 默认 true，failurePolicy 默认与之对应的 report_required_failure 或 report_optional_omission，不能组合出相互矛盾的值。visualIntent、suitePreference 存在时严格校验其字段，不接受任意规则或脚本。标签集合不接受重复项，总数最多 64；排序规范化只用于查询身份，不改写作者数据。

完整请求解析成功不代表 mock 已支持所有条件。离线桥接仅执行类型、硬标签及其现有摘要字段；非空环境、尺寸、角色、软偏好、套系等返回 unsupported_constraint/unsupported_capability，不静默降级。fields 绑定分页上下文，内部 Page 可由新增 `CommunitySearchResponse` 投影为 offline-summary.v1 JSON；完整发现响应与其他检索能力仍待实现。

Query 身份按确定顺序编码标签、环境模组和字段集合，包含契约与当前排序策略，忽略 requestId/needId/cursor/pageSize；pageSize 由游标另行校验。缺省值与显式默认值相同，字段重排和集合重排不改变身份。桥接游标与原内部 API 游标也不混用。

## 测试和后续工作

`CommunityOfflineTest` 覆盖真实包分组、不同来源许可、原字节保留、独立 hash 校验、下载上限、目标环境、不可变发布、跨 authority 拒绝、分页快照及到期、撤回检查和查询变更拒绝。已加入 `scripts/verify-component-assets.ps1` 的默认回归范围。

2026-11-03 隔离回归构建成功，269 项测试通过，失败/错误/跳过均为 0，其中新增离线社区测试 9 项；文档链接检查通过。尚未进行真实远端或游戏内验收。

同日完成搜索请求适配后，隔离回归共 278 项通过，失败/错误/跳过均为 0，新增 `CommunitySearchContractTest` 9 项，覆盖文档示例、严格格式、结构预算、条件保留、规范身份和分页上下文。

后续完成真实服务的能力说明、Range 续传、完整检索能力，以及所选来源/收据在本地去重、配方和建筑锁中的绑定。当前验证仅证明下载与独立缓存保留所选来源，尚未证明安装后或建筑锁仍保留该来源。此门槛通过前，不开放远端 AI 自动安装/生成。
