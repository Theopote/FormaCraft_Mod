# Formacraft

[![Build](https://github.com/Theopote/FormaCraft_Mod/actions/workflows/build.yml/badge.svg)](https://github.com/Theopote/FormaCraft_Mod/actions/workflows/build.yml)
[![License: CC0-1.0](https://img.shields.io/badge/License-CC0%201.0-lightgrey.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.10-green.svg)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://adoptium.net/)

**Formacraft is a semantic architecture compiler for Minecraft.**

Formacraft 是一个基于 [Fabric](https://fabricmc.net/)（Minecraft 1.21.10）的 AI 建筑生成模组。玩家通过**自然语言 + 空间工具**描述建筑意图，由 AI 规划、生成、预览并落地方块修改。

> Formacraft 的目标不是「自动放方块」，而是把 Minecraft 中的建筑行为从**操作级**提升到**语义级**。

---

## Features

| 能力 | 说明 |
|------|------|
| AI 驱动 | 自然语言描述建筑意图，支持 OpenAI、DeepSeek、Ollama 等兼容 API |
| 空间工具 | 选区、路径、轮廓、对称、构件放置等约束工具 |
| 语义构件 | 可复用的建筑语义单元（门、窗、柱等），支持 Socket 自动匹配 |
| Skeleton 系统 | 拓扑优先的建筑生成管线 |
| Memory 系统 | 建筑演化与增量修改 |
| Preview & Undo | 所有结果可预览、可裁剪、可撤销 |

### Design principles

- AI **never** calls `setBlock` directly — all world writes go through `BlockPatch`
- Every change is **previewable** and **undoable**
- Tool constraints are enforced throughout the pipeline

---

## Quick start

### Prerequisites

- **Java 21+**
- **Python 3.12+**
- **Gradle 8+**（项目自带 Wrapper，无需单独安装）
- **Minecraft 1.21.10** + [Fabric Loader](https://fabricmc.net/use/)

### 1. Clone the repository

```bash
git clone https://github.com/Theopote/FormaCraft_Mod.git
cd FormaCraft_Mod
```

### 2. Configure the Python backend (optional but recommended)

```bash
cd python_backend
cp .env.example .env
# Edit .env — set OPENAI_API_KEY or another LLM provider
pip install -r requirements.txt
```

Without an API key, the backend falls back to rule-based generation so you can still run and test locally.

### 3. Start the backend

```bash
# from python_backend/
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

The orchestrator listens at `http://localhost:8000`. API docs: `http://localhost:8000/docs`.

### 4. Build and run the mod

```bash
# from repository root
./gradlew build        # Unix / macOS
.\gradlew.bat build    # Windows

./gradlew runClient    # development client
```

The mod can **auto-start** the local Python backend on game launch (see in-game settings). You can also configure the orchestrator URL and LLM provider there.

---

## Architecture

```
Player (natural language + tools)
        │
        ▼
Tool Layer  →  PromptAssembler (RAG + constraints)
        │
        ▼
Python Backend (LLM → Blueprint / Skeleton JSON)
        │
        ▼
Compiler Pipeline  →  BlockPatch
        │
        ▼
Preview / Apply / Undo  →  Minecraft World
```

See **[ARCHITECTURE.md](ARCHITECTURE.md)** for the full system overview.

---

## Project layout

```
formacraft/
├── src/main/java/com/formacraft/   # Fabric mod (client, server, common)
├── src/main/resources/             # assets, lang, fabric.mod.json
├── python_backend/                 # FastAPI orchestrator + LLM services
│   ├── app/routes/                 # /build, /edit, /blueprint, …
│   └── app/services/               # ai_planner, llm_client, …
├── docs/                           # developer & design documentation
├── .github/workflows/              # CI (Gradle build + Python smoke)
├── ARCHITECTURE.md
├── CONTRIBUTING.md
└── LICENSE
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | System architecture overview |
| [docs/FORMACRAFT_DEVELOPER_DOCUMENTATION.md](docs/FORMACRAFT_DEVELOPER_DOCUMENTATION.md) | Detailed developer guide |
| [python_backend/README.md](python_backend/README.md) | Backend API & configuration |
| [python_backend/INTEGRATION_GUIDE.md](python_backend/INTEGRATION_GUIDE.md) | Java ↔ Python integration |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |

---

## Configuration

### In-game settings

Open Formacraft settings in Minecraft to configure:

- LLM provider (`auto` / `openai` / `deepseek` / `ollama` / …)
- API key and model
- Orchestrator endpoint (default `http://localhost:8000`)
- Auto-start local Python backend

Settings are saved to `config/formacraft_settings.json`.

### Backend environment variables

Copy `python_backend/.env.example` to `python_backend/.env`. Key variables:

| Variable | Description |
|----------|-------------|
| `OPENAI_API_KEY` | API key for OpenAI-compatible services |
| `OPENAI_MODEL` | Default model (e.g. `gpt-4o-mini`) |
| `LLM_PROVIDER` | Provider hint: `openai`, `deepseek`, `ollama`, … |
| `LLM_BASE_URL` | Base URL for compatible APIs |
| `DEEPSEEK_MODEL` | Model when using DeepSeek |

See [python_backend/README.md](python_backend/README.md) for the full list.

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

Areas where help is especially valuable:

- AI / prompt design
- Skeleton generators & geometry
- Component & Socket system
- Tools, UI / UX
- Documentation, examples, and tests

---

## License

This project is released under **[CC0 1.0 Universal](LICENSE)** (public domain dedication).

You may use, modify, and distribute this software for any purpose without attribution. See the [LICENSE](LICENSE) file for the full legal text.

---

## Disclaimer

Formacraft is an independent open-source project. It is **not** affiliated with, endorsed by, or associated with Mojang Studios or Microsoft. *Minecraft* is a trademark of Mojang Studios.

---

## Support

- [Open an issue](https://github.com/Theopote/FormaCraft_Mod/issues) for bugs or feature requests
- See [SECURITY.md](SECURITY.md) for reporting security vulnerabilities
