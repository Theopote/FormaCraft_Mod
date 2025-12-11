# FormaCraft 项目结构文档

## 项目概述
FormaCraft 是一个 Minecraft 模组，通过添加新的形状和建筑工具来增强游戏体验，使玩家能够更轻松地创建复杂的结构和设计。

## 文件结构详解

### 根目录 (Root Directory)
- `.github/` 
  - GitHub 相关配置
  - 包含 CI/CD 工作流文件
  - 问题模板和拉取请求模板

- `gradle/`
  - Gradle 包装器文件
  - 用于项目构建自动化
  - 包含 wrapper 配置

- `python_backend/` - Python 后端服务
  - `app/` - 后端主应用代码
    - 处理 API 请求
    - 数据逻辑处理
    - 外部服务集成
  - `requirements.txt` - Python 依赖包列表

- `run/`
  - 运行时生成的文件
  - 配置文件
  - 临时数据存储

- `src/` - 主要源代码目录
  - `main/` - 主源代码
    - `java/` - Java 源代码
      - `com/` - 包结构
        - 包含所有模组的 Java 类文件
    - `resources/` - 资源文件
      - `assets/` - 游戏资源
        - `textures/` - 纹理文件
        - `models/` - 3D 模型
        - `lang/` - 多语言文件
      - `data/` - 数据文件
        - 配方
        - 战利品表
        - 进度

### 配置文件
- `.gitattributes`
  - Git 属性配置
  - 文件行尾和编码设置

- `.gitignore`
  - Git 忽略规则
  - 排除不需要版本控制的文件

- `build.gradle`
  - Gradle 构建脚本
  - 依赖管理
  - 构建配置
  - 任务定义

- `gradle.properties`
  - Gradle 属性配置
  - JVM 参数设置
  - 项目属性

- `gradlew` / `gradlew.bat`
  - Gradle 包装器脚本
  - 跨平台构建支持
  - 自动下载 Gradle

- `LICENSE`
  - 软件许可证
  - 使用条款
  - 版权信息

- `settings.gradle`
  - Gradle 项目设置
  - 包含的子项目
  - 构建脚本类路径

## 主要组件说明

### Java 源代码 (`src/main/java/com/`)
- 方块实现
  - 自定义方块类
  - 方块状态处理器
  - 方块实体

- 物品系统
  - 物品定义
  - 物品行为
  - 合成配方

- 事件处理
  - 游戏事件监听
  - 玩家交互处理
  - 世界生成事件

- 网络通信
  - 客户端-服务端通信
  - 数据包处理
  - 网络协议

- 用户界面
  - GUI 组件
  - HUD 元素
  - 屏幕控制器

### 资源文件 (`src/main/resources/`)
- 纹理资源
  - 方块纹理
  - 物品图标
  - 粒子效果

- 模型文件
  - JSON 模型
  - 方块状态定义
  - 物品模型

- 本地化
  - 多语言支持
  - 翻译文件
  - 文本格式

- 声音
  - 音效文件
  - 背景音乐
  - 声音事件

### Python 后端 (`python_backend/`)
- Web 服务
  - RESTful API
  - WebSocket 支持
  - 用户认证

- 数据处理
  - 数据库交互
  - 文件处理
  - 缓存管理

- 集成服务
  - 第三方 API 集成
  - 数据同步
  - 外部服务通信

## Building the Project

1. Ensure you have Java Development Kit (JDK) 8 or later installed
2. Run the Gradle build:
   ```bash
   ./gradlew build  # On Unix/Linux/Mac
   .\gradlew.bat build  # On Windows
   ```

## Contributing

Contributions are welcome! Please read our contribution guidelines before submitting pull requests.

## License

This project is licensed under the terms specified in the `LICENSE` file.

## Support

For support, please open an issue in the issue tracker or contact the development team.

## Development
- Requires Java 21, Fabric Loom, Yarn mappings.
- Build: `./gradlew build`
- Run Client: `./gradlew runClient`
- Run Server: `./gradlew runServer`
