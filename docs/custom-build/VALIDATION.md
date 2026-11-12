# 本次交接资料验证（2026-11-13）

## 仅涉及本次整理，不是重新验收 App

在 coding 的既有 PowerShell 7.6.9 运行 `scripts/Test-Handoff.ps1`：

- 18 份收录 PowerShell 脚本通过语法解析。
- 71 份资料/脚本逐项存在与 SHA256 清单一致（MANIFEST.json 自身不入清单）。
- 新 Run-Build.ps1 在真实 ssh-p1、ops-v2 工作树分别运行默认 Inspect，正确报告源码 HEAD 与 .sshprobe/.ops 包名；不执行 Gradle，执行前后 Git status 一致。
- 两条源码线分别测试 release 未带 AllowOfficialPackage 时提前拒绝，不进入 SDK/构建阶段。
- Update-Manifest.ps1 在随机临时副本中实际运行并校验更新后全部文件哈希，临时目录由测试自身清理。
- 最终输出：`HANDOFF_TEST_OK parsedScripts=18 files=71 assertions=249`。

源码历史检查：对上游 fe5a28f9 之外两条分支的 12 个自定义提交，扫描 167 个唯一变更 blob；同时扫描新交接内容，未发现 GitHub/provider token、云 AccessKey 或完整私钥材料。初筛的 3 处 PRIVATE KEY 头字符串分别来自公钥校验测试与 UI placeholder，人工核对仅为头文本，不含私钥内容。此检查不宣称对第三方上游全历史做了完整安全审计。

整理后的入口、历史索引与脚本说明的相对链接已检查。历史记录保留原路径语境，原 source/、logs/、patches/、APK/ZIP 不作为已公开附件；用户原始 /upload 日志引用已去除。

## 没有做

没有运行 Debug/Release 构建、新 JVM 回归、APK 核验、Android 安装、Windows 真机、手机 IPC 或 VS Code 完整连接；没有修改 coding 原源码/分支、主机或 CT 配置。STATUS 中的构建/设备结果全部明确沿用既有记录。

交接提交使用 `[skip ci]`；现有 daily-build 仅 schedule/workflow_dispatch，不在本轮触发，不手动运行其发布流程。默认 master、仓库可见性、上游许可和署名不变。
