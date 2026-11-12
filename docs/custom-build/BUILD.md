# 构建指南

## 两种环境不要混淆

源码与 Gradle 构建在 **coding Debian 构建机**，不是手机 PRoot。构建机原有目录如下，仅是现场记录，不表示可自动重建/修改 CT：

| 项目 | 既有路径 |
|---|---|
| 日用源码 | `/mnt/coding/rikkahub-build/src/rikkahub`，ops-v2 |
| SSH 源码 | `/mnt/coding/rikkahub-build/src/rikkahub-ssh-p0`，实际 ssh-p1 |
| SDK | `/mnt/coding/rikkahub-build/android-sdk` |
| Gradle 用户目录 | `/mnt/coding/rikkahub-build/gradle-home` |
| 旧 ops 入口 | `/mnt/coding/rikkahub-build/build.sh`，硬编码日用工作树 |
| pwsh | `/mnt/coding/rikkahub-build/tools/pwsh-7.6.9/pwsh` |
| 日志 / 私下交付包 | `/mnt/coding/rikkahub-build/logs/`、`downloads/` |

**不能用旧 build.sh 构建 SSH：它会 cd 到日用源码！** 本仓库新入口接受 RepoRoot，默认只 Inspect，不修改代码、不自动提交、不中途格式化全库。coding 原工作树本轮未写入 GitHub 资料提交；若要使用新入口，在新 clone 中执行，或从 fork 单独取脚本并显式传 RepoRoot。不要为了资料同步擅自切换活跃工作树。

## 工具链：以当前分支文件为准

整理核对 3.1.2 源码：JDK **21**（Kotlin/JVM target 17），Gradle **9.6.0**，AGP **9.4.0**，Kotlin **2.4.21**。历史 3.1.1 文档的 Kotlin 2.4.20 已不是当前值。

- Android：compileSdk 37 minor 2，SDK 包 `platforms;android-37.2`、`build-tools;37.0.0`、platform-tools；targetSdk 37 / minSdk 26。
- pnpm **10**；coding 既有 10.34.9。pnpm 9 会对 web-ui/pnpm-workspace.yaml 的格式报 packages missing/empty。
- PowerShell 7，优先使用现成 pwsh；不要为了测试更改 CT 资源。
- 当前 3.1.2 两条线**没有 Git 子模块**（无 .gitmodules / mode160000）；`material3/material-color-utilities` 已包含 45 个普通受控文件。旧构建文档把它叫子模块，不要因此重复 clone/覆盖。以后上游重新引入子模块时，按当时 .gitmodules 处理。

## 新机器准备顺序

1. clone 指定分支。当前无子模块，先读 STATUS，确认 debug 应是 `.ops` 还是 `.sshprobe`。
2. 安装 JDK21、对应 SDK、Node/pnpm10、pwsh7；国内环境直接选国内镜像，不先做一轮试速。不要照抄历史内存/swap配置。
3. 配 JAVA_HOME、ANDROID_HOME/ANDROID_SDK_ROOT、GRADLE_USER_HOME，将 java/platform-tools 加 PATH。在 web-ui 运行 `pnpm install --frozen-lockfile`（推荐 `--registry=https://registry.npmmirror.com`）。
4. 准备 **未入 Git** 的 local.properties（sdk.dir；release 需要仓库外签名的 storeFile/storePassword/keyAlias/keyPassword）。不要打印密码或从别人环境复制不明配置。
5. GitHub flavor 应用仍使用 google-services 插件，需本地 `app/google-services.json`。个人无 Firebase 构建可自己生成明确的非生产占位配置，client 包名必须覆盖目标 applicationId；不要提交真实配置。下面是结构示例，按选中分支填包名，无可用 Firebase 凭据，不保证 Firebase 功能：

```json
{
  "project_info": {"project_number": "000000000000", "project_id": "rikkahub-localbuild", "storage_bucket": "rikkahub-localbuild.invalid"},
  "client": [{
    "client_info": {"mobilesdk_app_id": "1:000000000000:android:0000000000000000", "android_client_info": {"package_name": "me.rerere.rikkahub.sshprobe"}},
    "api_key": [{"current_key": "local-build-placeholder-not-a-real-key"}]
  }],
  "configuration_version": "1"
}
```

若同时构建 release，另加一个 `me.rerere.rikkahub` client；ops debug 用 `me.rerere.rikkahub.ops`。上游不一定忽略 google-services.json，在 `.git/info/exclude` 显式忽略它及私密配置，并在每次提交前检查。

6. Gradle wrapper 下载失败时可在**本机**把 distributionUrl 换成腾讯 Gradle 镜像对应的 9.6.0 zip。coding 的 wrapper 已有这份未提交差异，本次没把它混入源码提交；不要随手 revert 用户修改。依赖缓存、JVM heap/worker 配置留在机器私下配置，不写成通用参数强制下发。

## 新的统一入口（pwsh）

```powershell
# 只读确认源码、包名与版本，不需要 SDK，也不建日志目录
pwsh -NoProfile -File docs/custom-build/scripts/Run-Build.ps1 -Stage Inspect

# 显式指定源码和既有构建环境；完整 debug + androidTest + JVM + lint + 格式检查
pwsh -NoProfile -File docs/custom-build/scripts/Run-Build.ps1 `
  -Stage Debug -RepoRoot /path/to/rikkahub `
  -BuildHome /mnt/coding/rikkahub-build

# release 和官方同包名，必须额外显式许可；不会安装或发布
pwsh -NoProfile -File docs/custom-build/scripts/Run-Build.ps1 `
  -Stage Release -AllowOfficialPackage -BuildHome /mnt/coding/rikkahub-build
```

`-JavaHome`、`-AndroidHome`、`-GradleHome`、`-LogRoot` 可单独传入；不传 BuildHome 时使用现有环境变量。入口不安装依赖、不生成密钥/私有配置、不运行 git add/commit、不自动 spotlessApply；任一阶段失败都会保留阶段退出码，最终非零。不因继承 lint 失败吞掉其它检查结果。

上游基础命令仍是 `./gradlew :app:assembleGithubDebug` / `:app:assembleGithubRelease`、`:app:testGithubDebugUnitTest`、`:workspace:testDebugUnitTest`、`:app:lintGithubDebug`、`spotlessCheck`。不要写只有渠道不明确的 assembleDebug 来混淆产物。

## web-ui 必须算进全量构建

`:web:buildWebUi` 挂在 preBuild；全量出包不使用 `-x :web:buildWebUi`。纯 Kotlin 诊断可以临时跳过但必须标注，不能宣称全 APK 成功。包有了也不等于 UI、PRoot、Windows 或 VS Code 验收完成。

## 签名与安装

既有 debug/release key 在构建机仓库外，仅能私下安全备份。新机未恢复相同签名不能无损覆盖；release 与官方同包名，标准 Android 签名规则下不能覆盖官方签名包。用户设备曾有特定环境放行，但这不是通用安装保证。本次不改签名策略、不自动安装、不卸载、不清数据。

每次交付独立核验 APK 包名/version/ABI/证书/CRC/DEX与资源/两端SHA256；不能用旧 APK 的 hash 或源码中的常量冒充新产物核验。
