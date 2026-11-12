# RikkaHub 自用改造：源码、构建与 AI 交接

整理日期：2026-11-13。仓库：[tsxc-github/rikkahub](https://github.com/tsxc-github/rikkahub)。这是个人维护的 fork，不是上游官方发行版。

> **AI 先读根目录 [AGENTS.md](../../AGENTS.md) 的 Custom build 一节，再读本页与 [STATUS.md](STATUS.md)。不要把历史文档的“正在构建”当成当前后台任务。**

## 两条源码线

| 分支 | 用途 | 整理前源码提交 | 基线 |
|---|---|---|---|
| `ops-v2` | 日用：有界读文件、不可变结果引用、grep/glob、Markdown 大预算、历史投影 | `3adc2271d3f8a5f745ee5015d8fef012942ca825` | 上游 3.1.2，`fe5a28f9` |
| `ssh-p1` | 隔离实验：在上述 ops 上加手机 SSH/SFTP、LAN/热点与短 IPC 候选修复 | `198983c032cfccd866a62889c46a37bf01e83b20` | 包含 `ops-v2@3adc2271` |

两条 GitHub 分支在上述源码 tip 后增加资料提交，**不是重新编译的版本**。默认 `master` 未改；两条分支保存完整可达源码历史，不只是快照或补丁。SSH 尚未合并到日用线。

## 从哪里看

1. [STATUS.md](STATUS.md)：通过/失败/未验证的明确边界，签名与包名表。
2. [BUILD.md](BUILD.md)：本机真实目录、工具链、干净机器准备、构建命令、签名与 web-ui。
3. [AI-NOTES.md](AI-NOTES.md)：容易改坏的地方、环境限制、操作边界。
4. [MAINTENANCE.md](MAINTENANCE.md)：合并上游、验收、交付、回退的固定流程。
5. [scripts/README.md](scripts/README.md)：新构建入口与历史脚本的适用范围，**部分旧脚本会 git add/commit，不能盲跑**。
6. [history/INDEX.md](history/INDEX.md)：历史设计、实测与诊断材料；只按明确基线使用。
7. [MANIFEST.json](MANIFEST.json)：收录资料及来源的 SHA256 清单（不包含该清单自身）。
8. [VALIDATION.md](VALIDATION.md)：本轮资料/脚本验证范围，不与 App 既有测试混称。

## 快速拿源码

```powershell
git clone --branch ssh-p1 --recurse-submodules https://github.com/tsxc-github/rikkahub.git
# 日用线则选 --branch ops-v2；不要让手机同时安装错包名的 APK。
cd rikkahub
pwsh -NoProfile -File docs/custom-build/scripts/Run-Build.ps1 -Stage Inspect
```

Inspect 只核对 Git/包名/版本，不构建、不安装依赖、不提交源码。完整准备与构建见 BUILD.md。

## 这次没有上传什么

不含 APK、Windows 交付 ZIP、签名密钥、local.properties、真实 google-services.json、SDK/Gradle/pnpm 缓存、Rootfs、手机聊天/工作区数据、用户原始诊断日志、GitHub/SSH 授权。本仓库公开；私密资料不得用“只是给 AI 看”作为提交理由。

保留上游根 README、AGPL-3.0 许可、归属及内含的第三方代码；当前没有 Git 子模块，color utilities 已是普通受控文件。本目录没有替换许可。上传整理不是宣称已完成新的 Android、Windows 或 VS Code 实测。
