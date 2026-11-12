# 给接手 AI 的注意事项

## 先判断你在哪儿

- 构建机 coding 是 Debian/Linux，有 JDK/Android SDK/Gradle/pnpm 与完整 Git 历史。
- 手机 AI Workspace 是 Android App 内 PRoot/Alpine：无真实 namespace/capability，不能用 Docker/systemd/bwrap/mount；也不是构建机。手机共享存储不等于 /workspace，SAF 不是普通文件路径。
- 本地目录名 `rikkahub-ssh-p0` 很旧，实际分支已经是 **ssh-p1**；先 `git status --short --branch`，不能看目录名猜分支。
- 手机工作区 .git 对象曾被底层存储损坏；若 bad object HEAD，不把源码工作文件也删了。先核对构建机/远端可恢复的历史。
- 当前构建机参数、内存/swap/CPU/磁盘/网络配置均不得为了构建自行修改。任何宿主/CT 配置修改先问用户；文档里的资源值不是持续授权。

## 协作纪律

先给范围与回退方案并落盘，再改代码或机器。不顺手修无关 lint、重构架构、升级工具链、重装 Rootfs 或删除 VS Code Server。查明根因后记录结论；继续调参要有用户意图。

新的测试与构建编排优先 PowerShell `.ps1`。长构建放后台，记录阶段退出码；可后台监视构建进度，不能把“进程还在”当作成功。不要因为日志静默就重复启动 Gradle 或杀掉其他构建。

读源码优先有界原生 read/grep/glob/result。不要对整个 /workspace 递归 grep；不要用 shell cat 承载大输出（手机 shell 后端约 128 KiB 截断）。shell 只承担执行，别重新搭一层 opspipe。

## ops-v2 的不变量

- Markdown 默认 512 KiB 原文预算，豁免普通 32 KiB 历史正文折叠；普通文本分页。不是所有文件无限输出。
- `workspace_read_file` 的 cursor 是精确字节续读；长行、多字节 UTF-8 也要无丢失/无重复。不要拿行 offset 瞎拼代替 cursor。
- `workspace_result` 恢复不可变快照，不重跑 shell；结果过期必须明确报告，不能 ref 套 ref/二次投影吃掉 cursor。
- grep/glob 有命中与字节预算，必须诚实报告 partial/omitted。源端已经截掉的字节不能靠 ref 虚构恢复。
- 后台 shell start/output/kill 也是有界投影的一部分；保留 taskId/status/logPath/exitCode 与 skippedBytes/source_truncated，不把 running 当 error 或成功退出。
- 新逻辑尽量放独立文件，上游文件只留少量 ops 钩子；跟上游后检查 AgentLoop/ChatToolFactory 等真实实现，旧文章行号不可靠。

## SSH 安全与 IPC 边界

- 默认回环/显式选定 LAN IPv4，禁止偷偷监听 0.0.0.0、IPv6 或公网。地址消失即停；无路由器端口映射、UPnP、自动开防火墙。
- 电脑只把公钥交给手机；电脑私钥不得出电脑。host fingerprint 从手机 UI 取值，不能相信网络扫描自己给的指纹。
- SSH 服务选中的工作区必须明确；“列表有一项”不等于用户已勾选。不要为灰按钮卸载重装或清数据。
- 短 IPC bind 仅用于 SSH 会话和专用探针，不改全局 `/tmp`、普通工具 shell 或终端。guest 挂载点先创建；PRoot -b 不会代建目录。
- 保留 owner、路径长度、权限、symlink、目录非空、碰撞隔离检查；不要递归清缓存或删不属于当前任务的 socket。
- glibc Node 的 ENOENT 可能是 ELF loader 缺失，不等于文件没下载。已证实 CLI/musl Node 可运行；不要继续盲装 glibc/换发行版。
- 普通 coding Linux socket 成功不等于手机 PRoot 成功。手机新路径 listen+connect 通过之前不宣布 VS Code 修复；旧/tmp失败也不等于新路径失败。

## 公开仓库与凭据

严禁提交/回显 local.properties、真实 google-services.json、*.jks/*.keystore、私钥、授权 token、服务 cookie、手机用户数据与原始诊断日志。签名只能从仓库外取用；不得把鉴权放在 Git remote URL 或命令行。先审 diff，再 push；禁止 force push 和擅改默认分支。

AGENTS.md 的上游说明保留，但本 fork 的安全、包名、签名、流程以本节和本目录为准。后续新事实先更新 STATUS.md/对应文档，不能只留在聊天或模型记忆中。
