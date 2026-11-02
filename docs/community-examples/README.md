# 社区契约示例

这里的 JSON 是未来接口的数据示例，不是现有模组可导入的构件包，也没有对应运行服务。

- search-request.json：单个尖拱窗需求，尺寸已锁定。
- search-response.json：摘要范围允许候选，但实际适配尚未知。
- release-record.json：平台发布身份与原包声明分离，固定完整包及其他指纹。
- selected-release-binding.json：未来 adapter 在本地去重后仍需保留的来源选择与下载收据。

示例 ID、游标和全为重复数字的 hash 都是合成占位值，没有对应真实包或下载地址。验证示例时只检查 JSON 语法、字段边界、交叉引用和“未知不冒充已验证”等约束，不声称这些 hash 已通过真实包校验。actual_fit 的未知值不能用作直接世界安装依据。

接口说明见 [远端契约](../COMPONENT_COMMUNITY_API.md)，实体规则见 [领域模型](../COMPONENT_COMMUNITY_DOMAIN.md)。
