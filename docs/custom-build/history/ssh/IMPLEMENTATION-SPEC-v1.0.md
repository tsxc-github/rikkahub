# RikkaHub 内置 SSH 开发桥接：AI 开发实施规格

> 版本：1.0 ｜ 日期：2026-11-05 ｜ 目标代码基线：`rikkahub/rikkahub` 的 `feat/background-shell` 分支
> 用途：将本文直接交给 Coding Agent 作为实施任务书。**请先核对当前 checkout 的实际 API 与提交版本，不要机械照抄示例。**

## 0. 给 Coding Agent 的执行指令

你需要在 RikkaHub 的现有 Android/Kotlin/Jetpack Compose 架构上，实现一个**由用户主动开启、主动关闭的 SSH 开发连接功能**。手机的 **本地 PRoot Rootfs 和 `/workspace` 是唯一主工作环境**；电脑通过 SSH/SFTP 或 VS Code Remote-SSH 访问同一份文件，不做镜像同步。

**先做最小技术验证（P0），再做集成（P1）；不要在 SSH 登录尚未打通之前大规模改 UI。** 开工前阅读仓库 `AGENTS.md`，以仓库实际代码为准；每阶段提交可审查的改动、测试结果和明确的阻塞项。切勿声称未经真机测试的功能已成功。

### 成功体验

1. 用户在手机 RikkaHub 的「开发连接 / SSH」页面选择一个**本地且已安装 Rootfs** 的工作区。
2. 用户添加电脑的 **SSH 公钥**（不上传私钥），主动点击「启动」。
3. App 在该工作区的 PRoot 内启动 **OpenSSH `sshd`**，显示地址、端口、服务状态、服务器主机公钥指纹和「停止」入口；运行期间有符合 Android 要求的前台通知。
4. 电脑可用原生 `ssh` 登录并操作手机的 `/workspace`；`/skills` 按现有 PRoot 挂载规则可见。
5. 电脑可通过 SFTP 读写工作文件；如系统兼容，VS Code Remote-SSH 可进一步安装并运行 VS Code Server。
6. 用户关闭开发连接后，SSH 监听、相关 PRoot 子进程和通知被释放；RikkaHub 日常聊天/Agent 工作保持不变。

## 1. 范围与非目标

**P0 / P1 目标**：单一工作区、按需启动的 SSH 服务；密钥认证；本地/局域网两种监听模式；可观测的生命周期；直接文件访问；停止时清理。按已有工作区能力读写，不额外复制项目。

**明确不做**：全天候守护、开机自启、跨网公网穿透、双向同步、Rootfs 镜像同步、自动安装 VS Code Server、在 APK 内打包 VS Code Server、从零重写 SSH 协议、自动把 Agent 的聊天状态同步到电脑。

**P2 可选项**：VS Code Remote-SSH 兼容优化、只允许 SFTP 的专用模式、自动超时/断开提醒、连接日志和诊断导出。注意：**SFTP-only 不等于强制限制在 `/workspace`**；真正的路径隔离须单独设计和审计，不得声称 `internal-sftp` 自动完成隔离。

## 2. 已核实的源码锚点（2026-11-05）

| 文件 | 已有能力 / 注意点 |
| --- | --- |
| `workspace/src/main/java/me/rerere/workspace/ProotShellRunner.kt` | `startBackground(context, id)` 使用独立 `ProcessBuilder`；PRoot 参数包含 `--root-id`、`--kill-on-exit`、`-b files:/workspace` 及附加挂载。 |
| `workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt` | `startBackgroundCommand(root, command, cwd, id, shellCompatibilityMode)` 是可复用的工作区后台启动入口；`filesDir` / `linuxDir` 分离。 |
| `workspace/src/main/java/me/rerere/workspace/WorkspaceBackgroundCommand.kt` | `waitFor()`、`readOutput()`、`kill()`；PRoot 进程清理需遵循现有逻辑，**不要只调用 `Process.destroy()`**。 |
| `app/src/main/java/me/rerere/rikkahub/service/BackgroundShellManager.kt` | 原后台任务以**聊天会话**为所有者、仅在内存保存。**不能直接作为 SSH 服务生命周期管理器**，以免删除聊天导致 SSH 断开。 |
| `app/src/main/java/me/rerere/rikkahub/service/BackgroundShellForegroundService.kt` | 后台命令的前台服务参考；当前使用 `dataSync`，存在 Android 版本相关超时限制。 |
| `app/src/main/java/me/rerere/rikkahub/web/WebServerManager.kt`、`service/WebServerService.kt`、`ui/pages/setting/SettingWebPage.kt` | 可参考服务管理、状态流、设置 UI、前台通知等实现风格，**不能复用 Ktor HTTP 引擎做 SSH**。 |
| `app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt` | 支持工作区查询与类型区分；需校验只允许本地、Rootfs READY 的工作区。 |
| `app/src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt` | 挂载表包含 `/skills`、`/builtin_skills`、`/tool_outputs`、`/upload`；SSH 继承相同 PRoot 可见范围。 |
| `app/src/main/AndroidManifest.xml`、`AGENTS.md` | 前台服务声明、通知/网络权限、Material 3 Expressive 设计规范、构建命令。 |

以上是供定位的**源码锚点**，不是锁定的 API 契约。实现前先 `git rev-parse HEAD`，重新检查目标分支是否变化。

## 3. 建议架构

```text
Android / RikkaHub
  SettingSshPage (选择工作区、公钥、端口、启动/停止)
          │
          ▼
  SshDevService (符合 Android 规则的前台服务 + 通知)
          │
          ▼
  SshDevManager (独立于 Conversation 的状态机、配置、健康检查)
          │
          ▼
  WorkspaceRepository / WorkspaceManager
          │  startBackgroundCommand(...)
          ▼
  ProotShellRunner → 本地 Rootfs → sshd -D -e
                                       │
             /workspace + /skills ←─────┤ SSH Shell / SFTP
                                       ▼
                               电脑 SSH / VS Code
```

### 新增组件（建议命名，可根据现有架构调整）

- `app/.../service/SshDevService.kt`：用户主动开启时启动前台服务；通知含工作区、监听端口、停止动作；不接管聊天后台任务。
- `app/.../ssh/SshDevManager.kt`：`StateFlow<SshDevState>`、`start()`、`stop()`、`observeExit()`、端口/配置/权限校验、错误处理；对外暴露单一会话状态。
- `app/.../ssh/SshDevState.kt`：`STOPPED / STARTING / RUNNING / STOPPING / ERROR`，含 `workspaceId`、`port`、`listenMode`、`address`、`message`、`startedAt`。
- `app/.../ui/pages/setting/SettingSshPage.kt`：符合仓库 `AGENTS.md` 的 MD3 Expressive、`CardGroup` 风格，集成现有导航与字符串资源。
- 现有 Koin 注入、设置持久化、Manifest、通知渠道、导航与 i18n 按仓库方式接入。

### 生命周期约束

1. **默认关闭**；仅用户前台明确操作可启动；不随开机、打开聊天或 App 冷启动自动恢复。
2. 一个实例同一时刻仅服务一个工作区。重复「启动」幂等；切换工作区先停旧进程再启动新进程。
3. 以 `WorkspaceBackgroundCommand` 句柄作为 PRoot 进程所有权依据；等待退出必须在 IO 线程完成，不能阻塞主线程。
4. `sshd` 必须以前台方式运行：`-D -e`，避免守护化后与 PRoot `--kill-on-exit` 行为冲突。
5. 启动成功的判据是配置检查通过 + 进程存活 + 端口/本地握手可用，**不能仅以 `ProcessBuilder.start()` 成功判定 RUNNING**。
6. 停止时发起已有 PRoot 任务的安全终止，等待释放并清理通知；超时进入 ERROR，允许用户重试清理。
7. App 进程被系统杀死、手机休眠、网络切换、前台服务超时均可能中断服务；下次启动应检查并清理孤儿状态，不要假定 24×7 存活。
8. 工作区删除、Rootfs 损坏、App 被强制停止时，不得保留一个看起来仍在运行的「绿色」状态。

## 4. SSH 服务实现与安全基线

### 4.1 前提检查

- 仅允许本地 PRoot 工作区，且 `WorkspaceManager.hasRootfs(...)` 为真；拒绝已有的 **SSH 远程工作区**，避免把客户端工作区当作手机服务端。
- 先探测 Rootfs 内 `sshd`、`ssh-keygen`、`/bin/bash` 是否可用。首版**不自动执行 `apt install`**；缺少依赖时给出可复制的安装命令和明确提示。
- 确认 `/run/sshd`、`/root/.ssh` 存在且权限正确；初始化服务器 **host key**，保存在该工作区可持久化的 Linux 文件系统中。
- 用户粘贴/导入的是电脑公钥，如 `ssh-ed25519 AAAA...`；校验格式，拒绝换行注入、控制字符及重复项；保证 `authorized_keys` 文件权限为 `600`、目录权限为 `700`。
- 不在日志、设置或 UI 存储电脑**私钥**；不要打印密钥、API Token、Shell 环境变量或工作区文件内容。

### 4.2 建议 SSHD 配置（示例，不是未经验证的最终配置）

```sshconfig
Port 2222
ListenAddress 127.0.0.1
HostKey /etc/ssh/ssh_host_ed25519_key
PermitRootLogin prohibit-password
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
AuthorizedKeysFile /root/.ssh/authorized_keys
AllowTcpForwarding yes
AllowStreamLocalForwarding yes
AllowAgentForwarding no
GatewayPorts no
X11Forwarding no
PermitTunnel no
Subsystem sftp internal-sftp
LogLevel VERBOSE
```

- **默认仅监听 `127.0.0.1`**；为了手机与电脑通过 Wi-Fi 直连，可由用户主动切换「允许局域网连接」，显式提示风险后监听 `0.0.0.0`（或实际可用的 Wi-Fi 地址）。UI 不得将监听所有网卡误称为仅 Wi-Fi。
- 使用 `sshd -t -f <配置路径>` 验证配置。运行时使用固定路径与已校验的端口，而不是把 UI 任意字符串拼成 Shell 命令。启动命令形态：

```bash
exec /usr/sbin/sshd -D -e -f /etc/ssh/rikkahub_sshd_config
```

- 自生成配置文件需要安全的原子写入、严格的权限、可重入初始化，以及失败时的明确报错。首次连接应让用户核对 **服务器 host key 指纹**，不要自动关闭主机密钥校验。
- **重要安全事实**：完整 SSH Shell 可能访问 Rootfs 和所有现有挂载（包括 `/skills`、`/upload` 等），并非严格限制在 `/workspace`。必须在 UI 中提示这一点，避免使用公网端口映射；建议仅受信任 LAN / 私人 VPN / USB 调试通道。
- 首版不允许未认证访问、密码登录、root 的空密码登录，不允许擅自开放到公网；项目内敏感文件暴露范围需作为安全测试项。

### 4.3 Android 前台服务

- 参考 `WebServerService` 的实现结构，但**不要直接复制 `BackgroundShellForegroundService` 的 `dataSync` 类型**作为 SSH 的默认方案。
- 必须按实际用途审核 Android 14+ 的服务类型声明、权限、启动限制和通知逻辑；`specialUse` 需要真实用途声明及 Play 政策审查，`connectedDevice` 也有适用条件，不能为了规避时限随意选择。
- Android 15+、target SDK 35+ 的 `dataSync` 前台服务在后台存在累计 6 小时/24 小时限制；前台服务也不等于永久保活。
- 失败、用户取消、系统超时都应确保状态一致，不能留下「UI 显示已关闭、端口却仍然监听」的情况。

## 5. VS Code 支持定义

**不要把“SSH 可用”与“VS Code Remote-SSH 可用”混为一谈。**

- **普通 `ssh`**：只需手机端 SSH Server，可直接远程执行命令。
- **SFTP / SSHFS / 基于 SFTP 的编辑器扩展**：不需要 VS Code Server，适合轻量 Review、查看 Diff、修改文件；实际 Git/语言服务可能在电脑本地运行。
- **微软官方 VS Code Remote-SSH**：会在手机的 Linux 环境**安装并启动 VS Code Server**，供远程文件服务、扩展宿主和调试使用。它不是只连一次 SSH 就结束的纯文件传输功能。
- 远程端需检查 ARM64（`uname -m`）、glibc、libstdc++、内核版本，以及 `bash`、`tar`、`curl/wget`。以测试时**最新的微软官方系统要求**为准；PRoot 属于非标准运行环境，不能承诺所有扩展兼容。
- 不要在 RikkaHub APK 中预打包微软的 VS Code Server；按官方 Remote-SSH 客户端的正常流程安装。
- 如 PRoot 不兼容 VS Code Server，仍应交付可用的 SSH + SFTP 功能，而不是整个功能失败。

## 6. 分阶段开发任务与验收

### P0：技术可行性门槛（最优先）

**尽量不改 UI。** 在测试工作区用现有 `startBackgroundCommand()` 或可重复的开发入口启动已配置的 `sshd -D -e`。

必须真实验证并记录：

- [ ] `sshd -t` 正常，能够监听端口；无权限/`chroot`/`setgroups`/PAM 阶段错误。
- [ ] 电脑 SSH 公钥登录成功：`ssh -p 2222 root@<phone-ip> 'pwd; ls -la /workspace'`。
- [ ] 交互式登录成功，PTY、Shell、`git status` 正常。
- [ ] `sftp -P 2222 root@<phone-ip>` 能读取、上传、重命名测试文件，手机 Agent 能立即看到文件变化。
- [ ] 端口转发能力正常（为 Remote-SSH 预备）。
- [ ] 停止后台任务后，SSH 服务断开、监听端口释放，相关 PRoot 子进程不遗留。
- [ ] 记录测试的 Android 版本、芯片架构、Rootfs 发行版、PRoot 参数、OpenSSH 版本、构建提交哈希。

如果 P0 SSH 登录失败，**先诊断 PRoot 对 OpenSSH 的支持**，给出失败日志和最小复现；不要继续开发 UI 以掩盖阻塞。

### P1：内置 SSH 管理（交付目标）

- [ ] 新建独立于聊天会话的 `SshDevManager`；状态机、启动/停止、异步任务观察、错误分类和超时完整。
- [ ] 新增 `SshDevService`：前台通知与「停止」动作，正确处理服务停止/超时；符合当前 target SDK 和 Android 政策。
- [ ] Compose 设置页面：工作区选择、端口、回环/局域网模式、SSH 公钥增删、Host Key 指纹、状态与日志尾部。
- [ ] 安全配置：默认关闭、默认回环、本机/局域网风险提示、公钥认证、无 Shell 注入、无凭据泄露。
- [ ] 单实例与幂等、端口占用/Rootfs 缺失/密钥错误/权限拒绝/工作区删除等异常分支处理。
- [ ] 不破坏现有终端、Agent Shell、聊天后台命令、Web UI 服务；支持普通 SSH 与 SFTP。
- [ ] App 进程异常退出后，UI 不会误报仍在运行；重新启动不自动开放 SSH。

### P2：Remote-SSH 兼容与优化（P1 后）

- [ ] 在 Windows/macOS/Linux 的 VS Code 中用官方 Remote-SSH 连接手机；验证自动下载/启动 VS Code Server、打开 `/workspace`、Git Diff、编辑保存、终端运行。
- [ ] 测试手机 ARM64 + PRoot 的文件监听、端口转发、Shell/PTY、语言服务器内存开销；记录不兼容扩展。
- [ ] 如兼容，补充连接步骤、性能提示与连接诊断；不兼容则清楚提示推荐 SFTP 编辑模式。
- [ ] 可选：SFTP-only 配置、闲置时自动停止、连接会话摘要、调试日志导出。

## 7. 测试命令示例

> 请替换 IP、端口及密钥路径。第一次连接前核对 UI 展示的服务器 Host Key 指纹；不使用 `StrictHostKeyChecking=no` 规避校验。

```bash
# 电脑端：普通 SSH
ssh -i ~/.ssh/id_ed25519 -p 2222 root@PHONE_IP

# 电脑端：单次命令验证 /workspace
ssh -i ~/.ssh/id_ed25519 -p 2222 root@PHONE_IP 'uname -m; pwd; ls -la /workspace'

# 电脑端：SFTP
sftp -i ~/.ssh/id_ed25519 -P 2222 root@PHONE_IP

# 手机端：Rootfs 的基本兼容信息
uname -m
uname -r
ldd --version
command -v sshd
/usr/sbin/sshd -t -f /etc/ssh/rikkahub_sshd_config
```

在仅回环监听且**启用了 Android USB 调试**的测试场景，可在电脑上使用 `adb forward tcp:2222 tcp:2222`，随后连接 `127.0.0.1:2222`；这是测试通道，不要求最终用户依赖 ADB。

## 8. 回归、性能与交付要求

- 不做侵入式改动：优先扩展 `app` 层；共享 `workspace` 模块只有发现不足时再以最小补丁修改。
- 相关 IO / `waitFor()` / `kill()` 必须在后台 Dispatcher 执行，避免主线程卡顿。
- 所有长生命周期工作必须有明确 owner 和停止路径；避免因重复点击产生多份 `sshd`。
- 需要记录 App 退后台、锁屏、Wi-Fi 切换、断网重连、杀进程、FGS 被拒绝、Android 15+ 超时等行为，不承诺永久存活。
- 完整 SSH 使用者可访问敏感挂载；不得声称此模式实现严格文件沙箱。
- 新增必要的单元测试、可测试的状态机逻辑和手动真机测试清单。
- 构建/检查以仓库 `AGENTS.md` 为准；基线命令包括：

```bash
./gradlew assembleGithubDebug
./gradlew test
./gradlew lint
./gradlew spotlessCheck
```

**最终交付给用户**：源代码改动与 diff、APK（如构建环境允许）、修改文件列表、配置/使用指南、SSH 和 SFTP 真机测试记录、VS Code Remote-SSH 兼容报告、已知限制和回滚步骤。每阶段给出「已验证 / 未验证 / 失败」状态，不能将理论可行写成测试通过。

## 9. 验收时的最短端到端场景

1. 手机用 RikkaHub Agent 在 `/workspace/demo` 创建 `hello.txt`。
2. 手机上主动开启「SSH 开发连接」，添加电脑公钥、显示端口和 Host Key 指纹。
3. 电脑使用 SSH/SFTP 打开同一目录，将 `hello.txt` 内容改成 `reviewed-on-pc`。
4. 回到手机 Agent，`cat /workspace/demo/hello.txt` 必须立即得到 `reviewed-on-pc`；**没有任何 Git 同步步骤**。
5. 手机关闭 SSH；电脑新建 SSH 连接应失败，Rootfs 中原项目和 Agent 功能继续正常。
6. 可选：在 VS Code Remote-SSH 完成同样操作并记录 VS Code Server 性能。

## 10. 参考资料（与实现相关）

- RikkaHub 源码分支：<https://github.com/rikkahub/rikkahub/tree/feat/background-shell>
- PRoot 后台启动：<https://github.com/rikkahub/rikkahub/blob/feat/background-shell/workspace/src/main/java/me/rerere/workspace/ProotShellRunner.kt>
- 工作区管理：<https://github.com/rikkahub/rikkahub/blob/feat/background-shell/workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt>
- 任务进程清理：<https://github.com/rikkahub/rikkahub/blob/feat/background-shell/workspace/src/main/java/me/rerere/workspace/WorkspaceBackgroundCommand.kt>
- 后台任务所有权：<https://github.com/rikkahub/rikkahub/blob/feat/background-shell/app/src/main/java/me/rerere/rikkahub/service/BackgroundShellManager.kt>
- Web UI 前台服务（供设计参照）：<https://github.com/rikkahub/rikkahub/blob/feat/background-shell/app/src/main/java/me/rerere/rikkahub/service/WebServerService.kt>
- 仓库开发规范：<https://github.com/rikkahub/rikkahub/blob/feat/background-shell/AGENTS.md>
- VS Code Remote-SSH：<https://code.visualstudio.com/docs/remote/ssh>
- VS Code Linux 依赖：<https://code.visualstudio.com/docs/remote/linux>
- Android 前台服务类型：<https://developer.android.com/develop/background-work/services/fgs/service-types>
- Android 15 前台服务限制：<https://developer.android.com/about/versions/15/changes/foreground-service-types>

---

**实施原则：先证明 `sshd` 在 RikkaHub 的 PRoot 内能完成真实登录，再做可靠的按需服务；SFTP 是 P1 必须实现的可用底线，官方 Remote-SSH 是独立的兼容性验收项。**
