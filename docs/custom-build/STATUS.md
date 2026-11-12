# 当前状态与证据边界

## 阅读规则

本页是 2026-11-13 资料整理时对既有记录的核对；没有在本轮重跑 APK/JVM/真机测试。检查必须区分：源码编译、JVM 测试、静态 APK 核验、普通 Linux 探针、Android 真机、Windows 客户端、VS Code 完整窗口。一个层级通过不代表下一个层级通过。

## ops-v2（日用线）

- 源码 `3adc2271`，合并上游 3.1.2 `fe5a28f9`；旧 3.1.1 的 `a4341901` 不是当前 tip。
- 既有记录：debug/release 全量构建成功，包含 web-ui；构建前后 41 项 Kotlin/JVM 回归各通过。
- 3.1.1 v2 端到端：17 项通过；不能把它作为 3.1.2 全部实机验收。
- 3.1.2 arm64 release 静态核验与本地/构建机 SHA256 一致。旧记录没有单独完成其装机验收的证据。
- APK SHA256：`716c6109be35f76c909a91e9507916cf5b5f19b431f6119e23343eed2635425d`。
- 证据：history/ops/UPSTREAM-MERGE-2026-11-07.md、V2-DEVICE-TEST-2026-11-06.md。

## SSH / LAN / IPC（实验线）

| 阶段 | 源码锚点 | 既有证据 | 不能扩写成 |
|---|---|---|---|
| P0 | `7f784084` | 用户确认电脑 SSH、PTY、SFTP、转发与停止清理通过 | 正式服务/UI 已完成 |
| P1 | `fe1a683c` | 用户确认设置页启动 → 电脑 SSH 成功 | 通知停止、指纹持久化、所有生命周期项都已逐项确认 |
| LAN | `96b94e15` | 用户反馈无线连接好使 | 同 Wi-Fi/热点所有矩阵都有逐项日志 |
| P2 IPC 候选 | `198983c0` | APK/androidTest 构建及静态核验，683 项 JVM 通过；其中核心 73 项；coding Node 4 项 listen/connect 通过 | 手机新映射通过，或 VS Code Remote-SSH 全链路通过 |

**P2 未通过。** 已知手机旧 `/tmp` Unix socket 返回 EINVAL；可运行 CLI/musl Node，而传统 glibc Node 缺 loader。短路径与 PRoot 翻译是候选根因，不是已证明的唯一根因。新方案只对 SSH 绑定 `/vscode-tmp` 并设置 TMPDIR；先由用户在手机 App 内运行 IPC 探针，再考虑 VS Code 窗口验收。

SSH 最终既有 driver 为 **exit 1**：assemble 与 JVM 阶段通过，lint 仍有继承的 67 errors / 348 warnings / 1 hint，spotless 有继承的 WorkspaceRepository.kt / WorkspaceReadChunk.kt 两文件问题。不能写成“所有检查通过”；也不能未经比对将新的失败一律标成继承。

IPC APK SHA256：`4865d9b93e92e1590ca06730ac93d76bd016445752d1b53077259ad307b46fee`；androidTest：`b155622a2a49a7bf00a30872532e7017b00d8da5a26dd0d95fc81b8f9d6d4959`。本次未上传二进制。

## 包名、版本、签名

| 变体 | 包名 | 用途 |
|---|---|---|
| ops-v2 githubDebug | `me.rerere.rikkahub.ops` | 可与官方并存 |
| ssh-p1 githubDebug | `me.rerere.rikkahub.sshprobe` | 只用隔离测试数据/Rootfs |
| githubRelease | `me.rerere.rikkahub` | 与官方同包名，不是安全并存实验包 |

基线 versionName **3.1.2** / versionCode **195**。versionName 随上游同号，不加 `-ops`；官方更新提醒保留，**不能点官方 APK 下载来更新改造版**，应先合并上游再自编译。

既有自编译 release 证书 SHA256：`35448b679c3366f6e277156079fae32cbd4c60661b4126c1db55220059448201`；debug：`7fb5c6c9015483f1c11518069f4817924d4c07ca5c937744a93d18831a2c9630`。证书指纹是公开核验值，不是私钥。仓库不提供相应私钥，新机器自己生成签名不能冒充原签名或无损覆盖升级。不要自动卸载/清数据来解决签名不符。
