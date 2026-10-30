# 单构件离线包 v1

状态：离线交换与校验 API。尚未接入游戏导入界面、自动入库、多构件事务或网络发布。

## 文件格式

建议扩展名 `.fccomponent.json`。文件为一个 JSON 对象，schema 为 `formacraft.component.package.v1`，包含 manifest、componentJson、sha256。componentJson 是完整原型 JSON 的字符串，sha256 是该字符串 UTF-8 字节的 SHA-256；这样保留原型的精确内容，不依赖重序列化的字段顺序。不是 ZIP，不解压包内路径。

manifest 包含 namespace、version、author、license、trainingPermission、minecraftVersion、requiredMods、blockNamespaceOwners、derivedFrom。训练许可为 ALLOWED / DENIED / UNSPECIFIED，与资产共享许可独立，缺省 UNSPECIFIED。namespace 与构件本地 ID 分开，完整身份为 namespace/componentId@version，不改变现有本地 ID 规则。

requiredMods 为模组 ID 到精确版本的映射，minecraftVersion 也精确匹配。读取时由调用者提供运行环境，不自动下载依赖。非原版方块的命名空间必须能对应已声明依赖；命名空间与模组 ID 不同时，通过 blockNamespaceOwners 指定归属，不硬编码两者相等。derivedFrom 保留作者提供的来源引用，不自动访问链接。

## API

- ComponentPackageIO.write(path, definition, manifest)：在写文件前校验构件、元数据、依赖声明及大小；只创建新文件，不覆盖已有文件。
- ComponentPackageIO.read(path, environment)：有界读取，校验 schema、哈希、元数据、构件规则与环境兼容性，返回 Verified。读取不会写库、创建目录或改变世界。
- Verified.component() 每次返回新副本，避免调用者修改后污染已校验的原始载荷；完整身份与哈希固定。

首版复用 ComponentValidator、ComponentIds 的 ID、方块数量及 JSON 长度限制。包字节上限为 16 MiB。合法共享资产仍保留锚点、插槽、套系标签、来源和受控变化规则。哈希用于内容一致性，不证明作者身份或元数据真实性。

导入界面后续应先展示 Verified 的许可、训练授权与依赖信息，再通过现有玩家/服务器写权限流程安装；不能因读取成功而绕过安装权限。首版不导入可执行插件或其他预制件系统的 generator_class。
文件按严格 UTF-8 解码，未知训练许可值拒绝读取。环境校验依赖调用者提供的版本清单，不验证签名或实际方块注册表；实际安装与预览仍须由运行环境处理。
首版未包含外部 archetype 侧车、调色板或缩略图；读取成功不等于跨环境最终几何等价。后续安装阶段需处理这些依赖，并同时保留 manifest，避免来源和许可信息丢失。
最终验证：8 项包读写/校验测试与全部构件回归共 67 项测试通过。尚未接入游戏内导入界面和安装流程。
安装 API 已另行实现，见 COMPONENT_PACKAGE_INSTALLATION.md；read 仍不自动安装。包校验同时拒绝可逃逸的外部 archetypeRef。
