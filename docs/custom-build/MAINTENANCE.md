# 维护、验证、交付与回退

## 每次动手前

1. 读根 AGENTS.md、README.md、STATUS.md、BUILD.md；明确日用 ops 还是 SSH 实验，不跨线顺手合并。
2. 记录当前 branch/HEAD、`git status --short`、upstream 基线、目标包名与构建机目录。脏文件先辨认；wrapper 国内镜像通常是本机差异，不直接 reset/覆盖。
3. 写本轮 PLAN：目标、非目标、安全边界、验证矩阵、回退点。改变宿主/CT 配置、安装 App/删除数据或改网络需另行获准。
4. 独立分支/worktree，保留原 tip。不要在活跃工作树执行 checkout/reset/clean，不 stash 不明来源修改。

## 合并上游

- 用户 fork 为写入目标，上游 `rikkahub/rikkahub` 只读；先 `git remote -v`，不要被 origin/upstream 名字迷惑。
- versionName 跟上游同号，不加 `-ops`，保留更新提醒但不安装官方更新。
- 对当前 GitHub 的资料提交，合并时也要保留 AGENTS.md 自用补充和 docs/custom-build，不直接拿上游文件覆盖。
- 保留完整历史；已有 3.1.2 采用 merge，不能把 rebase 当无条件默认或 force push。SSH 包名 `.sshprobe` 冲突必须保留；release 官方包名风险不能扩散到 debug。
- 搜索并审阅冲突之外的语义变化：重复参数、工具名称/返回结构、后台 shell、cursor/ref 投影、后台任务状态、新版 workspace backend。
- 旧 3.1.1 mbox 不能恢复 3.1.2 merge；Git 分支历史是主备份。必要时对确切基线导出 `git diff --binary <base>..<tip>`，不要用会漏 merge 的 format-patch 冒充完整恢复补丁。

## 验证矩阵

| 层级 | 最低记录 |
|---|---|
| 快速核心/JVM | 执行入口、源码 SHA、测试数、退出码；缓存类/普通 Linux 局限 |
| 全量构建 | debug/release/androidTest 分开，web-ui 是否包含、阶段退出码 |
| 质量检查 | lint 与 spotless 单独报告；继承失败与新增失败必须有证据 |
| APK | 包名、versionName/code、ABI、签名证书、ZIP CRC、关键 DEX/资源标记与 SHA256 |
| 设备 | Android 真机/Windows 客户端具体步骤与结果，不从静态核验推导 |
| P2 | 手机新 `/vscode-tmp` 的 listen+connect，随后 VS Code 真正打开远程窗口；普通 Linux 不替代 |

本轮只整理文档时不要重新触发这些昂贵/有副作用的测试；写清“沿用哪份既有结果”。

## 交付

只发布用户要求的变体；通常先隔离 debug，不自动覆盖日用 release。记录完整 SHA、源码脏状态、包名/版本/签名、APK SHA256、日志位置、已验证/未验证、失败项、安装/回退办法。发布 APK 需用户另行确认，本目录不含二进制。

Windows 新脚本使用 pwsh，显式控制 UTF-8/LF 与退出码；不要用 CRLF 管道把 shell/Node here-doc 破坏掉。安装走 `-r` 不等于允许清数据；签名不符先解释，不卸载。host key 必须从手机 UI 核验。

## 回退

回退到事先保存的准确源码提交，在独立工作树构建。不要用 `git reset --hard` 或 `git clean -fdx` 清理带密钥/用户数据的工作目录。保留可用 LAN/P1 APK 与签名的私下备份；P2 失败不破坏已可用的 SSH 工作区。任何卸载、数据清理、系统参数变更都先问用户。

## 更新资料

最新事实改 STATUS.md；长排查另建有日期文档放 history 并在 INDEX 说明基线。MANIFEST.json 是本次导入的校验快照，资料修改时用 scripts/Update-Manifest.ps1 更新；不要让旧 PID/历史“待构建”冒充当前状态。
