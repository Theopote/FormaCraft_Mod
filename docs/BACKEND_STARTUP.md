# 从设置面板启动本机后端

更新：2026-10-04。

FormaCraft 设置面板底部新增“本机后端服务”。本机单人游戏可以直接使用；连接独立 Minecraft 服务器时，后端必须从服务器所在机器可访问，客户端启动自己的本机后端不能替代服务器端部署。

## 首次准备

模组目前不捆绑 Python 或后端依赖，也不会自动安装软件。准备项目的 python_backend 目录，包含 app/main.py 和 requirements.lock。首次在 PowerShell 中运行：

```powershell
cd F:\development\formacraft-1.21.10\python_backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.lock
```

如需后端环境配置，复制 .env.example 为 .env 并编辑；已有 .env 不要覆盖。启动器发现 .env 时传给 uvicorn，不打印文件内容。模型配置也可通过游戏中的模型服务设置传入。

Python 3.14 注意：当前 requirements.lock 中旧 jiter==0.9.0 没有对应的 Windows 二进制包，安装可能进入源码构建并失败。此环境可改用项目声明的兼容范围：`.\.venv\Scripts\python.exe -m pip install --only-binary=:all: -r requirements.txt`。这是兼容安装，不等于原锁定版本安装；其他环境不要据此直接重写锁文件。

## 面板配置与操作

- 后端地址：`http://127.0.0.1:8000`。
- 后端目录：`F:\development\formacraft-1.21.10\python_backend`，其他安装位置填写实际目录。
- Python 路径：留空自动检测虚拟环境和系统 Python；也可填写 `F:\development\formacraft-1.21.10\python_backend\.venv\Scripts\python.exe`。路径不加外层引号。
- 启动端口：`8000`，须与后端地址中的端口一致。

点击“保存并启动”只保存后端地址、目录、Python、端口和自动启动配置，不要求模型 API Key；其他模型设置仍通过原“保存”操作保存。状态显示启动/可用/失败，失败时悬停启动按钮可查看说明。点击“检查状态”执行异步 /health 请求，不启动进程。

启动仅支持 HTTP 的 localhost 或 127.0.0.1，绑定 127.0.0.1；远程地址不能触发本机启动。相同管理器内不会重复启动；已健康的外部服务可直接使用，但不取得该进程的所有权。“停止”只对本模组启动的存活进程启用。手动停止后暂停自动重试，点击启动恢复；游戏退出清理本模组持有的进程。没有 uvicorn reload 子进程。

自动启动开关保存后控制随游戏启动及周期重试。所有健康检查、解释器探测、等待和进程停止都在后台执行，不在渲染线程等待。健康检查要求 HTTP 成功及 JSON 的 ok=true；这只证明服务响应，不证明模型 API 配置可用。

日志位于游戏工作目录的 `logs/formacraft_orchestrator.log`，启动诊断位于 `logs/formacraft_backend_autostart.log`。目录不存在、Python 不可用、依赖缺失、端口不一致或进程退出时，先查看状态与日志。修改目录或端口前先停止已由模组启动的旧进程。

2026-10-04 的实际故障日志显示系统 py 有 uvicorn，却缺少 dotenv，读取 .env 时立即退出。解释器探测现检查 uvicorn、fastapi、pydantic、dotenv、requests、openai，避免仅凭 uvicorn 可导入就启动依赖不完整的环境；缺失依赖明确提示安装后端依赖。项目内 .venv 优先自动检测，无需把密钥写入启动命令。

该故障已在项目专用 Python 3.14 虚拟环境使用 requirements.txt 的兼容范围解决，pip check 通过，实际 app.main 可导入；带 .env 启动 uvicorn，在临时本机端口请求 /health 返回 ok=true，随后清理测试进程。run/config 的 Python 和后端目录已指向项目专用环境。此证据确认真实后端可启动，不等同于已在游戏面板内验证生命周期；需要重启游戏载入新模组和配置。原 requirements.lock 仍保留，未宣称其与 Python 3.14 兼容。

## 验证范围

LocalBackendLaunchTest 验证本机地址、远程/HTTPS/凭据拒绝、端口一致性、参数路径带空格、环境文件参数和远程手动启动不取得进程所有权。启动命令用 ProcessBuilder 参数列表，不经过 shell。完整 Java 检查覆盖面板编译；尚未实际启动 Minecraft 验收滚动、按钮交互与真实 uvicorn 启停。首次依赖安装和打包后的后端分发仍需准备，不宣称安装模组即可零配置运行。
