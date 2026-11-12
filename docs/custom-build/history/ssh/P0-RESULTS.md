# P0 实测记录

## 最新用户真机报告（3.1.2隔离包）

用户提交`logs/device-v312/p0-report-user.txt`：Android16/API36/arm64-v8a，OpenSSH10.3p1/OpenSSL3.5.7/musl1.2.6。报告逐项PASS：公钥命令与skills、强制PTY、Git、SFTP及Android侧即时读取、direct-tcpip、真实WorkspaceBackgroundCommand.kill后活动连接断开/端口释放/观察tracee消失，以及后续普通命令可运行。报告出现`P0 AUTOMATED CHECKS PASSED`且未见FAIL/CLEANUP ERROR；用户随后确认instrumentation结尾为OK。**自动真机验收通过（报告+用户确认）**；完整stdout未上传。随后用户运行computer模式，报告READY FOR COMPUTER，并在按指令执行Windows SSH登录、交互式PTY/命令/Ctrl-C、SFTP上传重命名下载后回复「完全没问题」；这些电脑手动项已由用户确认。随后按指令执行Windows本地端口转发（经12222再次SSH并输出标记）、电脑模式STOP/连接断开及最终instrumentation结束检查，用户再次确认「都没问题了」。**P0整体通过：依据已归档自动报告、用户确认instrumentation OK以及Windows手工验收确认。** 最终computer模式完整报告未上传归档，不声称所有原始日志已齐全。P1/P2尚未实现，FGS/休眠/系统强杀及VS Code Server仍是后续独立验收项。

用户观察测试App退出：Windows助手finally明确执行`am force-stop me.rerere.rikkahub.sshprobe`；探针本身也故意kill被测sshd/PRoot。日志末尾signal15/signal9与该清理一致，不能据此判为Android App闪退。若instrumentation为OK且退出发生在测试结束，符合脚本设计；若中途退出或正式App受影响，再查instrumentation/logcat。

报告`Baseline=068dea...`是探针源码写死的旧标签（SshP0DeviceTest.kt:41），rebase时未更新，不能以此断言安装了旧APK。当前已分发包经构建/签名核验对应7f784084、3.1.2/code195；下次改探针应改为动态BuildConfig/version及明确源码标记，不能拿该硬编码当运行时构建证明。

以下为先前独立回环测试与初始设计的历史记录。

## 1. 源码核对

- 已读目标分支根 `AGENTS.md`，目标分支 git tree 中仅此一份 AGENTS。
- 远端分支和现有 origin ref 一致：`068dea4917e534cb3ed186ae963df98e66c084a4`。
- 当前正在使用的 ops-v2 checkout 保持不动；SSH 探针位于独立 worktree `ssh-p0`。
- `WorkspaceManager.kt:208-238` 提供 `startBackgroundCommand(...)`，返回句柄；
  `ProotShellRunner.kt:34-45` 将日志/进程文件放在 Rootfs `/tmp/rikkahub/background`。
- `ProotShellRunner.kt:178-182` 写 shell/PRoot PID；`WorkspaceBackgroundCommand.kt`
  的 `kill()` 负责组信号和 tracee 清理。后续产品不能只 destroy 一个 Process。
- 聊天会话管理器仍有 conversation owner，因此不复用作正式 SSH 生命周期 owner。

## 2. 当前手机独立 PRoot 测试（真实执行）

执行：`python3 /workspace/projects/rikkahub-ssh/p0_probe.py`。
原始结果 `logs/p0-result.json`，服务日志 `logs/sshd.log`。

环境：aarch64；Linux `6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k`；
Alpine 3.24；musl 1.2.6；OpenSSH 10.3p1；OpenSSL 3.5.9。
实际 App UID 10420，CapEff 全 0，SELinux untrusted_app。
**Android 用户态版本/API 与具体芯片型号未从当前 PRoot 取得；不能以内核 android15 名字代替系统版本。**
该手机当前运行的是已安装的 ops-v2 App，不是新编出的目标分支 APK；此处记录 target_commit
只是被审查的源码基线，绝非当前安装 APK 的构建 SHA。

| 项目 | 结果 | 证据/边界 |
| --- | --- | --- |
| sshd -t | 通过（有非致命告警） | exit=0；Alpine 无 PAM 编译支持，`UsePAM no` 提示 Unsupported option |
| sshd -D -e 回环监听 | 通过 | 127.0.0.1:2222；未监听 LAN/公网 |
| 公钥认证+执行命令 | 通过 | exit=0，P0_AUTH_OK、/workspace；检查 /skills 存在 |
| 强制 PTY | 通过 | `ssh -tt`、`test -t 0`，P0_PTY_OK；不等于电脑人工交互会话 |
| git status | 通过 | 临时仓库 exit=0 |
| SFTP put/rename/get/rm | 通过 | exit=0，下载内容一致 |
| direct-tcpip 转发 | 通过 | `ssh -W` 的 echo 内容一致；不是完整 VS Code 验证 |
| 结束独立 sshd | 通过 | exit=0；新连接拒绝，端口释放 |
| App 后台句柄停止+活动连接清理 | 未验证 | 独立 subprocess 不能证明 Kotlin 句柄语义 |
| 电脑连接/交互 | 未验证 | 需 ADB 转发和用户电脑 |
| Android FGS/休眠/杀 App | 未验证 | P1 验收项 |
| VS Code Remote-SSH | 未验证 | P2，不阻塞轻量 SSH/SFTP 的方向 |

使用全新临时 Ed25519 host/client key，私钥位于临时 Rootfs /root 目录，finally 已删除；
只持久化指纹和受控日志，未读取/改写用户既有私钥或 authorized_keys。
主机校验使用本次生成的精确 host public key 写入临时 known_hosts，StrictHostKeyChecking=yes。

安装依赖走清华 Alpine 镜像：新增 openssh-server；OpenSSH 客户端/common/keygen 从
10.3p1-r0 升到同版本 r2（服务端与 common 要求 revision 一致）。未启用 init/rc 服务。
服务监听已关闭。包依赖保留供进一步开发，不擅自卸载原本已有的客户端。

## 3. ADB androidTest 探针（代码已准备，构建已启动；未实机运行）

- 独立 debug 包，显式 `workspaceId`；拒绝 SSH 远程工作区/Rootfs 非 READY。
- 不安装依赖、不修改账号/现有 authorized_keys；只在独立测试目录产生临时密钥。
- 使用真正 `WorkspaceManager.startBackgroundCommand()` 启动 sshd；FileObserver 等待日志与停止标记，无 sleep/循环轮询。
- 自动验证公钥、强制 PTY、Git、SFTP、Android 直接看同一文件、direct-tcpip。
- 自动持有一个活动 SSH 客户端，再调用真实句柄 `kill()`；检查 waitFor、端口绑定、已观察 tracee 的 PID+starttime 是否仍存在。
- computer 模式导入电脑公钥（首版探针只接受 Ed25519），回环监听，等待电脑测试；15 分钟上限，finally 尝试清理。
- **这些是待验证的测试设计，不是测试成功结论。**
- 不提供生产级崩溃恢复/FGS，因此测试期间不要当正式服务使用；异常退出可强制停止隔离测试包。

## 4. 构建/检查状态

后台任务已安排：spotlessApply → 独立提交/补丁 → assembleGithubDebug +
assembleGithubDebugAndroidTest → app/workspace JVM tests → app lintGithubDebug + spotlessCheck。
目前未读取最终状态，不能标记构建/检查成功。标准全仓 `test`/`lint` 尚未执行；本阶段先跑相关模块/变体。

## 5. 阶段结论

OpenSSH 在当前真机 PRoot 的真实协议链路已得到正面证据，没有出现 chroot/setgroups/PAM
导致登录失败的现象；但 P0 整体尚未通过。用户选择 ADB 路线，先交付隔离测试构建和命令，
不以“理论兼容”或独立回环测试替代 App 进程清理验收，不抢跑 P1 UI。
