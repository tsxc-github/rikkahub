# Custom build / 自用改造：AI 必读

本 fork 有 `ops-v2`（日用工具改造）与 `ssh-p1`（隔离 SSH/LAN/IPC 实验）两条线。**先读 [docs/custom-build/README.md](docs/custom-build/README.md)、[STATUS.md](docs/custom-build/STATUS.md)、[BUILD.md](docs/custom-build/BUILD.md)、[AI-NOTES.md](docs/custom-build/AI-NOTES.md)**，再按下面上游规范写代码。

- 先核对分支/HEAD/脏文件/包名；目录 `rikkahub-ssh-p0` 实际可能在 ssh-p1。不要顺手把 SSH 合入日用 ops。
- 先落盘规划，再修改；不能改宿主/CT 内存、swap、CPU、磁盘、网络/防火墙，除非用户明确批准。不要自动安装 App、卸载、清数据或重装 Rootfs。
- 测试编排优先 pwsh；新的 Run-Build.ps1 默认 Inspect，不提交源码。历史脚本部分会自动格式化/git commit，先读 scripts/README.md，禁止盲跑。
- `.ops` 与 `.sshprobe` debug 包名必须隔离；release 与官方同包名，签名密钥不入库，签名不同不能靠自动卸载解决。
- versionName 跟上游同号，保留官方更新提醒；不要下载安装官方 APK 覆盖改造。
- 保留 Markdown 512KiB/历史豁免、精确 cursor、不可变 ref、有界 grep/glob、后台 shell 状态和源截断报告，不用 shell cat/opspipe 绕过有界工具。
- SSH 默认回环或显式选定 LAN 地址；无 UPnP/公网映射/0.0.0.0。短 IPC 映射只限 SSH，不能改全局 /tmp。
- **P2/VS Code 尚未验收通过**。683 JVM、APK 静态核验、coding Linux Node 成功都不能替代手机新路径与 VS Code 远程窗口证据；lint/格式继承失败不能写“全绿”。
- 禁止提交或回显私钥、*.jks/*.keystore、local.properties、真实 google-services.json、授权/cookie、Rootfs、手机工作区数据或原始日志。先检查 diff；不 force push、不改 master/默认分支。
- 定位靠具体目录，读/搜索源码优先有界原生 read/grep/glob/result；禁止递归扫描整个 /workspace。长构建后台执行，阶段退出码与未验证项写入文档。

以下保留上游指南。若其通用示例与上面的 fork 安全/流程约束冲突，以本节为准。

---

# Repository Guidelines

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material 3 Expressive (MD3 Expressive) design language.

## Design Language

The UI follows Material 3 Expressive. New and reworked screens should match it rather than the older baseline
Material 3 look (cards with dividers, plain forms).

- The app theme is `MaterialExpressiveTheme` (app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt); prefer the
  expressive variants of components, shapes (`MaterialShapes`), emphasized typography (e.g. `titleSmallEmphasized`)
  and motion from `androidx.compose.material3` over hand-rolled equivalents.
- Settings-style and form pages use segmented groups via `CardGroup` (
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt) instead of a card with dividers.
- Favor larger corner radii, filled/tonal containers, and whole-row click targets.

## Build, Test, and Development Commands

```bash
./gradlew assembleGithubDebug    # 构建 GitHub 渠道的 Debug APK
./gradlew assembleGithubRelease  # 构建 GitHub Release / 每日构建用的 APK
./gradlew bundlePlayRelease      # 构建上架 Google Play 的 AAB
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
./gradlew spotlessApply          # 用 ktlint 格式化相对 origin/master 改动过的 Kotlin 文件
./gradlew spotlessCheck          # 只检查格式不改文件
```

App 有一个 `distribution` 维度的 product flavor：`github`（默认）和 `play`，共用同一个 applicationId。
渠道差异用 `BuildConfig.IS_PLAY_STORE` 判断（如 Play 版不检查更新、不显示更新提醒）。

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **material3**: Material color utility extensions used by the app UI
- **ui**: Reusable Compose UI components that do not depend on app logic
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.
- **terminal**: Terminal emulator core taken from termux-app's `terminal-emulator` (package `com.termux.terminal`, kept
  binary compatible with the `terminal-view` aar). `TerminalSession` runs over a `TerminalTransport`: local PRoot
  terminals use `LocalPtyTransport`, SSH workspaces use the app's `SshTerminalTransport`. Origin and local changes are
  listed in terminal/README.md.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings.
  Once a conversation is persisted it also holds a `ConversationConfig` snapshot (chat model, reasoning level, search,
  MCP servers, workspace, skills) taken from the assistant; from then on chat-page changes to those settings stay on
  the conversation, and code should read them through `Settings.getAssistantOf(conversation)` /
  `Settings.getChatModelOf(conversation)` instead of the assistant directly. Once loaded, the in-memory
  `ConversationSession` is the single source of truth: change a conversation through `ChatService` (which applies
  `session.update { latest -> ... }` and then `flush()`), never by saving a whole `Conversation` snapshot or one read
  back from the database; deleting also goes through `ChatService` so a running generation cannot re-insert it. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt,
  app/src/main/java/me/rerere/rikkahub/data/model/ConversationConfig.kt,
  app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- Add, translate or clean up strings with the `tools/i18n` CLI (Bun); see the `i18n-localization` skill.
