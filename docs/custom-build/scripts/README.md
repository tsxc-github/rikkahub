# 脚本索引与执行风险

## 新入口

- `Run-Build.ps1`：默认只 Inspect；显式 Debug/Release 才调用 Gradle，记录分阶段日志与退出码。RepoRoot 可覆盖，不会 checkout/reset/format/git add/commit/安装 App；Release 需 AllowOfficialPackage。使用示例见 ../BUILD.md。
- `Update-Manifest.ps1`：资料修改后重算 ../MANIFEST.json 的文件 SHA256，保留原来源说明。只改清单，不构建。
- `Test-Handoff.ps1 -SshRepo <ssh源码> -OpsRepo <ops源码>`：解析收录的 PowerShell 脚本，验证资料哈希、Inspect 默认只读与 release 显式许可门槛；在私有临时副本测试清单更新器。不运行 Gradle 或手机测试。

新入口是本轮增加的编排，只有实际记录的语法/只读 smoke 结果；本轮**没有执行 Debug/Release，也不把旧的 683 JVM 当成新入口验证**。

## Windows 客户端：windows/

保留 Test-RikkaSsh/Get-VscodeProbe 等 `.ps1` 与原说明。脚本需要 Windows OpenSSH、ADB（视阶段）、私下保存的 APK 和用户自己的电脑密钥；仓库没有交付 ZIP/APK/私钥。先读各脚本 param 与说明，不默认端口/地址/指纹。

旧说明出现“当前目录的 APK/ZIP”时，是旧交付包语境，不表示 Git clone 后文件齐备；安装前先向用户确认包来源、哈希和手机 UI 指纹。不要自动安装，也不要把 -r 升级扩写成允许卸载。

## 历史构建与验收：legacy-ssh/、legacy-ops/

这些脚本为还原既有构建记录而保留，**不是通用、无副作用的工具**：

- Build-Ipc/Build-Lan、早期 build-p0/build-p1 等硬编码 coding 路径、日志目录、缓存与源文件；部分阶段自动 spotlessApply、git add、git commit、format-patch。
- 旧 Python 核心测试依赖已编译模块和 Gradle 缓存，版本/路径可能固定；不能替代干净机器 Gradle 构建。verify-v2-integration.py 通过 runpy 查旧外部路径，复制到别处并不会自动适配。
- 发布/核验脚本依赖 JDK/Android build-tools、现成 APK 与下载目录；不能当作自动 GitHub Release 发布工具。
- watch 脚本读取既有约定日志；历史 PID/exit-code 可能过期。不得把旧退出码当新构建成功。

**新任务从 Run-Build.ps1 开始。** 若要重跑历史核心验收，先读脚本、确认准确基线和路径、使用隔离工作树并显式批准副作用。不要一键运行整个 scripts 目录，不要照旧脚本操作正式 ops 工作树。
