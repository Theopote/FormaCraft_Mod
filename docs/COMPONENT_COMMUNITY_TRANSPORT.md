# 社区摘要响应与只读 HTTP 客户端

实现范围：离线索引的 `offline-summary.v1` 摘要投影，以及显式调用的只读搜索客户端。不部署服务，不自动连接网络，不注册游戏入口。HTTP POST 仅执行搜索，不发布、不安装、不写世界。

## 响应子集

契约仍为 `formacraft.community.search-result.v1`，新增 profile=`offline-summary.v1`，rankingPolicy=`offline-exact.v1`。此 profile 只执行已有精确类型/硬标签查询，不能声称完整设计示例的风格、尺寸和环境能力已实现。请求中的不支持条件继续明确拒绝。

每个响应包含 requestId/needId/queryFingerprint、具有稳定 id 与剩余有效期的 indexSnapshot、items、nextCursor、truncatedFields。游标继续绑定请求上下文和原 pageSize。投影在索引选页时参与 128 KiB 字节预算；放不下的组留给下一页，不在序列化后直接丢弃。

核心身份字段不随 fields 隐藏：分组配置指纹、发布引用、完整包描述、结构/配置指纹、sourceClaim、platformPublisher=null、discoveryRevision=1。null 表示未建立平台账户证明，不能从包内 namespace 推断。这里的 discoveryRevision=1 仅表示固定 fixture 摘要，尚无平台发现修订服务。

identity 请求增加 title，最多保留 256 个 Unicode 标量值，裁剪路径记入 truncatedFields；空名称回退为原 componentId。type 请求增加 key 与 declared 证据；rights 请求返回原始 license 和 trainingPermission，不合并来源。evidence 请求增加数据校验等级、runtimeTest=unknown、fit.actualFit=unknown、compatibility.status=unknown。requiredCapabilitiesStatus=unknown，不能把未提取的作者变化规则误认为不需要额外能力。始终不返回 blocks、componentJson、原始规则数组或本地路径。

客户端严格核对 profile、排序策略、请求关联、查询身份、页大小、快照有效期、分组与候选配置身份、整包双重声明、受信 authority、重复分组/发布、投影字段以及许可枚举。摘要校验成功不能代替下载后的本地数据、环境与适配校验。完整设计响应使用其他 profile 时先拒绝，待实现相应能力后再支持。

## 传输边界

客户端仅使用构造时指定的 authority 与 base URI，路径固定为 base 下的 v1/search；请求/响应不能改写目的地址。不发送账户令牌，不跟随重定向。生产地址必须 HTTPS；仅显式配置的数字回环地址允许 HTTP，供本地测试使用。base URI 不接受 userinfo、query、fragment 或路径跳转。

请求先通过现有严格解析及当前 profile 的能力检查，再发送；响应强制 JSON、identity 编码，声明长度与实际读取长度均受 128 KiB 限制。独立 Java HttpClient 不使用全局 CookieHandler、Authenticator 或代理。连接预算 5 秒，整个异步响应体接收的等待预算 30 秒；有界 BodySubscriber 在超额时取消订阅，截止时间到达或用户取消时中止异步请求。无自动重试。一次取消令牌对应一次调用，收到响应后仍重检取消状态。外层调用为阻塞式，接入游戏时必须由工作线程执行，不应放进主线程；持有者须关闭客户端。

成功响应交给严格投影解析器。错误状态使用有界错误消息契约，只接受已定义错误码，不把任意远端 message 或底层路径当用户提示；429 统一 rate_limited，5xx 统一 service_unavailable，重定向单独拒绝。原始包下载、断点续传、任务编排与持久下载收据仍未接通。

## 验收

使用自动绑定随机端口的本机 HTTP 测试服务验证真实搜索往返、摘要投影、分页预算、身份错配、重定向拒绝、超额响应、错误状态与取消；服务只在测试期间运行，最终关闭。回归仍使用隔离构件测试脚本。
