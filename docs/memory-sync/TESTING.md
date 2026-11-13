# 记忆交换与同步：自动测试

入口：`tools/memory-sync/Run-MemorySyncTests.ps1`（PowerShell 7）。默认执行真实 Host tests + APK build，不安装工具/依赖/App，不卸载、不清数据、不提交源码、不调整机器资源。只接受 `.memoryprobe` debug 包；release、官方包、`.ops`、`.sshprobe` 均不作为此测试入口的安装目标。

## Host 一键执行

```powershell
pwsh -NoProfile -File tools/memory-sync/Run-MemorySyncTests.ps1 `
  -BuildHome /mnt/coding/rikkahub-build
```

亦可分别传入 `-RepoRoot`、`-JavaHome`、`-AndroidHome`、`-GradleHome`、`-Python`、`-LogRoot`。不传 BuildHome 时使用已有环境变量。前置准备见 [自用构建指南](../custom-build/BUILD.md)：JDK 21、Android SDK、pnpm 10、已准备的 web-ui/node_modules、本地 google-services.json；入口只检测，不替你安装或生成配置。

顺序执行并独立记录：

1. Python `unittest discover -s tools/memory-sync -p test_*.py -v`：真实子进程 RPC/CLI、竞争写、异常输入、崩溃恢复等 helper 回归。
2. `:app:testGithubDebugUnitTest --tests '*Memory*'`：记忆 Repository、模型工具、Archive、同步引擎及真实 helper 集成测试。
3. `:workspace:testDebugUnitTest`。
4. `:app:assembleGithubDebug`。
5. `:app:assembleGithubDebugAndroidTest`。
6. `apk-verification`：无设备也核验 arm64 App 与 instrumentation APK 包名、目标包、ABI、相同签名和 SHA-256，并写入报告。

APK 阶段包含 `:web:buildWebUi`，不使用跳过 web 的参数；不运行自动格式化、提交脚本或依赖安装器。一个检查失败后继续其它独立检查，最终退出码非零；不把缺工具、零项 Python 测试或未执行的设备测试算作通过。

### Windows / WSL 的真实边界

脚本能由 Windows pwsh 7 启动并输出错误 JSON，但**全量 Host suite 不支持原生 Windows**：服务端 helper 依赖 POSIX flock/fsync/no-follow，既有 workspace 测试也使用 `/bin/sh`。入口明确失败，而不是只转发 Python 然后谎称全量兼容。请在已准备工具链的 Linux / coding 上执行，或从 Windows 启动 **WSL 内整套 Linux pwsh + JDK + SDK**：

```powershell
wsl --exec pwsh -NoProfile -File /absolute/linux/repo/tools/memory-sync/Run-MemorySyncTests.ps1 -BuildHome /absolute/linux/build-home
```

不自动安装 WSL、发行版或 Android SDK；WSL 的 ADB 设备连接也须预先准备。当前不提供原生 Windows DeviceOnly 模式。

## 显式设备测试

```powershell
pwsh -NoProfile -File tools/memory-sync/Run-MemorySyncTests.ps1 `
  -BuildHome /mnt/coding/rikkahub-build `
  -Device -InstallTestApp -Serial DEVICE_SERIAL -RequireDeviceSsh
```

- 不传 `-Device` 完全不调用 ADB，JSON 中 device 为 `NOT_RUN`。
- 只有显式 `-InstallTestApp` 才执行 `adb install -r -t`；不授权安装时只接受已安装且与本次产物 SHA-256 相同的隔离 APK。
- 选定 serial 必须在线且已授权；未选 serial 时必须恰好一台在线设备。要求 `arm64-v8a`，APK 从本次 Gradle output-metadata 选择 arm64 产物。
- 安装前核验 app/test 包名、instrumentation 目标包、ABI、APK 可验证签名；已安装包拉取 base.apk 仅作证书核验，随后删除该临时副本。签名不符直接失败，绝不靠卸载/清数据修复。
- instrumentation 仅过滤 `me.rerere.rikkahub.MemorySyncDeviceTest`，要求非空且明确的成功结果；ADB 命令退出 0 本身不代表测试通过。

### 一次性真实 SSH 端到端

Linux 且同时指定 `-Device -InstallTestApp` 时，`MemorySyncDeviceFixture.ps1` 使用**已有** sshd/ssh-keygen：

1. 在独立 0700 临时目录生成全新 client/host key、authorized_keys、sshd_config 和 helper 数据；不读取既有私钥，不写用户 home、系统 SSH 配置或服务。
2. 新 sshd 仅监听 `127.0.0.1` 随机高位端口；只允许当前运行用户和新公钥，禁密码/PTY/转发，ForceCommand 固定到本仓库 helper RPC 与临时数据根目录。使用系统现成 PAM account policy；若 sshd privilege-separation 目录、账户策略等不满足就失败，不改系统修复。
3. 建立仅此端口的 `adb reverse --no-rebind`；通过 `adb exec-in run-as` 把短期 JSON 密钥 fixture 写入 `.memoryprobe` 私有 files，密钥不进入阶段日志/JSON。
4. 设备通过实际 Koin WorkspaceRepository.saveSsh、MemoryRepository、MemorySyncManager 创建本次独占 SSH workspace/scope，连接已初始化远端空间；测试远端初始下拉、设备新增上推、远端新增下拉、双方修改冲突、真实设置/三方冲突 UI 手工合并动作、删除 tombstone。
5. 主机再次通过真实 helper RPC 独立核验最终合并正文及删除 generation。finally 删除设备私有 fixture、该 run 创建的 reverse、临时 sshd 进程和临时密钥/数据；设备只解除自己的 binding，删除自己创建的 scope/revision/workspace，不动其它 scope/工作区/Rootfs。

没有 sshd、非 Linux 或未授权 fixture 时 SSH 子覆盖明确 `NOT_RUN`；`-RequireDeviceSsh` 将这种缺失升级为失败。**没有设备时此链路未执行，不以 Host 结果冒充真机验收。**

## 设备覆盖与证据

`MemorySyncDeviceTest` 使用真实 Android Room（独立内存库/唯一临时持久库）、真实 Archive 流与新快照目录，覆盖原文/frontmatter/[stated]/CRLF/Unicode 保留、scope 隔离、幂等重导入、不删除无关篇、逐篇覆盖确认、预览后并发冲突、ABA、跨 Repository 并发 CAS、非法 ZIP/路径/UTF-8 零写入、超限/敏感内容逐篇失败、旧超限篇导出/缩小、Room 重开 tombstone 持久化。

`MemorySyncProbeActivity` 仅位于 `app/src/debug`、`exported=false`，由 instrumentation 启动，不提供外部 ADB/第三方 backdoor，release 不包含。常规 UI 使用独立 Room fixture；真实 SSH 测试才注入隔离包的实际 Koin 服务及本次独占 scope。自动检查全局/助手入口、真实导入预览/应用/并发冲突，并打开后取消真实 Markdown/ZIP/SAF 目录/ZIP 导出系统选择器，不枚举或选择用户文件。系统 picker 的选定文件和持久授权完成流程不由此取消测试替代；核心导入导出以真实流/磁盘测试完成，不伪称已跑系统 DocumentsProvider 全实现矩阵。

截图和 accessibility XML 在 fixture App 界面内生成（不截图系统文件选择器的用户文档列表），主机归档 global、assistant、import、import-result、export、conflict；真实 SSH 执行时另有 sync-settings、sync-conflict。失败时另外尝试 ADB screenshot + uiautomator hierarchy。设备证据暂留隔离包 `external-files/memory-sync-tests/<runId>`，不会自动清除其它运行归档。

## 输出与退出码

默认日志位于系统临时目录 `rikkahub-memory-sync-tests/<时间-随机ID>/`，可用 `-LogRoot` 指定：

- 每阶段 `.log`、`<stage>-exit-code`；原生命令失败保留实际退出码。
- `result.json`：schemaVersion、主机/模式、阶段 PASS/FAIL/NOT_RUN、起止时间、错误、APK SHA-256/证书公开指纹、device/SSH 执行状态。
- `exit-code` 与进程退出码：任一失败为 1，其它为 0；没有设备仍明确 `device=NOT_RUN`，并非设备通过。

日志/证据只应包含测试 fixture；公开发布前仍需人工审查，不上传临时私钥、APK、真实 google-services.json、用户文件或私有配置。本文描述自动化能力，不宣称这次已跑过 Host/设备；集成验证由主代理集中执行。
