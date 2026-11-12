# 更新提醒逻辑：现状与改造方案

2026-11-05 核对 `master 3.1.1`（`ops` 分支同底）。

## 现状（三处代码）

| 位置 | 作用 |
| --- | --- |
| `utils/UpdateChecker.kt` | 拉 `https://updates.rikka-ai.com/`（JSON：`version/publishedAt/changelog/downloads[]`），UA 带 `VERSION_NAME #VERSION_CODE`；`downloadUpdate()` 用系统 DownloadManager 下 APK |
| `ui/components/ui/UpdateCard.kt:83-84` | `Version(BuildConfig.VERSION_NAME)` vs `Version(info.version)`，有新版就出卡片；点下载 → 下官方 APK |
| `ui/pages/chat/ChatVM.kt:210` + `ChatDrawer.kt:197` | 开关条件：`!BuildConfig.IS_PLAY_STORE && !settings.init && updateCheckDisabledUntilEpochMillis <= now` |
| `SettingPreferencesNotificationPage.kt:114` | 设置里给用户「暂停 N 天」的入口（写 `updateCheckDisabledUntilEpochMillis`） |

`Version` 是完整 SemVer 实现，**预发布低于正式版**：`3.1.1-ops < 3.1.1`。

## 现在这个 fork 会出什么事

1. **必然会误报**：我们的 `versionName = 3.1.1-ops`，官方源返回 `3.1.1` → 判为「有新版本」，抽屉顶部一直挂一张卡。
2. **更危险的是点下去**：卡片的下载地址是**官方 APK**。release 包已经用了官方包名 `me.rerere.rikkahub`，
   点一下等于把官方包下到手机，装上就**覆盖掉我们这份带 ops 功能的构建**（有核心破解，签名拦不住）。功能静默消失，只留一脸懵。
3. 上游发新版时更糟：`3.2.0 > 3.1.1-ops`，同样提示官方包。

## 四个选项

### A. 只改版本号形态（1 行，最省事）✅ 2026-11-05 已采用
`versionName` 改成 `3.1.1`（与官方一致）或 `3.1.2-ops`。
- 好处：一行，立刻不误报。
- 问题：**没解决第 2 条**。上游发 3.2.0 时照样提示官方包，一点就覆盖。

### B. 构建开关，直接关掉（3 行，推荐做）
`app/build.gradle.kts` 加 `buildConfigField("boolean","OPS_BUILD","true")`（`// region ops-build`），
两个调用点的条件改成 `!BuildConfig.IS_PLAY_STORE && !BuildConfig.OPS_BUILD && …`。
- 好处：彻底不请求官方源，不可能误触；改动面 3 处、每处 ≤2 行，rebase 友好。
- 代价：自己也收不到提醒（靠 `git fetch` 或我这边看）。

### C. 换成自己的更新源（1 行常量 + 托管一个 JSON）
`UpdateChecker.kt` 里 `API_URL` 换成自有地址（沿用上游 schema），`downloads[].url` 指向自己的 APK。
- 好处：保留「提醒更新」的能力，且下的是自己的包。
- 代价：每次出包要更新那个 JSON；需要一个能对外访问的静态文件（`slimevr.tsxc.xyz` 加个路由，或 status 服务的静态目录）。
- 细节：载入文件名建议 `rikkahub-ops-3.1.1-ops-arm64.apk`，别和官方包混；`version` 字段写成 `3.1.1-ops` 才能被 `Version` 正确比较。

### D. B + C（我倾向这个）
先做 B 保证不会误触；等真需要「提醒我自己有新包」时，再做 C，把 `API_URL` 指过去。
不加设置项 —— 任何 DataStore 字段都是迁移 + rebase 冲突，为这点需求不值。

## 实际决定（2026-11-05）

用户选择 **A**：`versionName` 改回 `3.1.1`（去掉 `-ops`）。**B/C 不做**。
理由：更新卡片对他是「上游发新版 → 该合并了」的信号，看到卡片会主动让我 rebase，所以不能把检查关掉（B），
也不值得为它维护一个自有更新源（C）。剩下的风险是「手滑点下载会覆盖本构建」——已知并接受。

## 推荐落地顺序（若将来要做）

1. **B**（约 10 分钟）：加 `OPS_BUILD` + 两处条件短路。编译验证。
2. 观察一段时间：如果「不知道自己有新包」这件事真的烦，再做 **C**，把 `API_URL` 指到自有 JSON。
3. 顺手把 `versionName` 的 `-ops` 保留（它现在只是显示用，B 之后不参与任何比较）。

## 注意

- `IS_PLAY_STORE` 那套逻辑**不要动语义**：Play 版本来就不提醒，我们只是在它旁边再挂一个开关，rebase 时容易对齐。
- `UpdateCard` 本身不用改；它只是「有新版就显示」，决定「要不要检查」的是 ChatVM 的条件。
- 若将来做 C：`UpdateChecker` 的 `catch` 已经会把失败变成 `UiState.Error`（卡片不显示），所以自有源 404 时是静默降级，安全。
