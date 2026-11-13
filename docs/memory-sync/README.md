# Markdown 记忆交换与 SSH 双向同步

开发基线：`tsxc-github/rikkahub` 的 `ops-v2@5ebbc986e68ad102d7ab6af4ca95fac10226779a`（3.2.0 / code 196）。特性分支 `feat/memory-markdown-sync`，不包含 `ssh-p1` 手机 SSH 服务实验。debug 包为独立 `me.rerere.rikkahub.memoryprobe`，release 包名不变；不要卸载日用 App 来安装测试包。

## App 内使用

助手 → 记忆 → **导入导出与同步**。标题下会显示目标是全局记忆还是当前助手记忆。

### 手动交换（无需服务器）

- **导出整库 ZIP**：系统保存对话框选择位置。包内为 `memories/` 原文 Markdown 和 `manifest.json`，不是数据库备份。
- 单篇编辑页的导出按钮保存原始 `.md`，不隐藏 frontmatter 或 `[stated]`。
- **选择 Markdown**：先填写目标虚拟路径，如 `/topics/servers.md`。
- **导入 ZIP（先校验与预览）** / **导入 SAF 目录**：校验完成后显示新增、相同、修改和拒绝项；替换已有内容需要逐篇确认。
- **应用新增与已确认篇目**：按预览的 generation 条件提交；预览后发生的编辑不会被覆盖。查看逐篇结果，失败项可重新预览后处理。
- **导出到本地工作区新快照**：明确选择本地工作区，新建独立快照目录，不覆盖旧快照。

导入是合并，不是整库替换：包里没有某篇不会删除 App 中的那篇。单篇、目录和 ZIP 导入都不会自动建立同步绑定。一般单篇新增/增大上限 48 KiB，导入最多 2,000 篇/64 MiB；旧超限篇可无损导出，但不能自动截断后导入。

导入严格检查 UTF-8、路径穿越、重复/冲突路径、软链接和 ZIP 大小预算。正文来自原始 `content`，保留 CRLF、未知 frontmatter 和内部标记，不以 UI 渲染后的正文替代。

### 自动同步（先准备 SSH 目标）

1. 在扩展/工作区配置一个 SSH 工作区，测试连接并核对主机公钥。无需安装手机 SSH 开发桥接。
2. 按 [helper 说明](../../tools/memory-sync/README.md) 将 Python 标准库 helper 复制到服务器账户的固定路径。此步骤不自动部署，不开新端口。
3. 在记忆交换页进入 **SSH 双向同步**，显式选择已有 SSH 连接。
4. 填服务器数据根目录，必须是普通工作区/rsync 镜像之外的独立绝对路径；不要填 `/` 或含软链接的路径。
5. 生成新的空间 UUID 后选择 **发布为新空间**；已有空间则填已有 UUID，选择 **连接已有空间**。
6. 首次配对逐篇比较，双方同名不同内容进入冲突，不按修改时间覆盖。

一个本地记忆库绑定一个空间；全局库按 `__global__` 共用一条绑定。切换助手的全局记忆开关不会合并或迁移另一个库。解绑不删除任一端内容。

前台本地修改短暂合并后触发同步，并周期读取远端；后台使用已有 WorkManager，网络可用时尽力执行。强制停止、系统回收或网络断开期间不保证实时。界面分开显示上次尝试、上次成功和冲突/错误。

SSH 主机、端口、账户或已确认主机公钥改变时，原绑定暂停，需解除后重新核对；不让旧同步基线自动跟随另一个端点。认证/主机公钥错误不能通过关闭校验解决。

## 外部 agent 的写法

远端 `ROOT/spaces/UUID/memories/` 可以按普通 Markdown 读取，不能直接覆盖、删除或用双向 rsync 镜像。正确流程是 helper 的 **checkout → 编辑草稿 → commit**，由 agent 工具包装自动调用；删除也必须携带读到的版本 token。

```sh
HELPER="$HOME/.local/share/rikkahub-memory-sync/memory_sync.py"
python3 "$HELPER" checkout --root /private/memory-data --space <UUID> \
  --path /topics/servers.md --draft /private/scratch/servers.md
# 用 agent 编辑草稿，保留旁边的 token sidecar。
python3 "$HELPER" commit --root /private/memory-data --space <UUID> \
  --draft /private/scratch/servers.md
```

完整命令、RPC、重试和恢复见 [helper README](../../tools/memory-sync/README.md)。CLI 的 token 取自原草稿，不允许提交旧正文时重新查询最新版 token 绕过冲突检查。

本功能没有把任意文件监听包装成“安全双向同步”：不配合协议的原地写入无法获得 CAS 保证。helper 检测到受管文件漂移会保存证据并停止，不选一方覆盖。

## 冲突与删除

- 双端都修改：保留 App 正式内容和远端内容，界面展示基线/双方原文；可保留任一端或手工合并。
- 用户提交解决方案仍校验其查看的双方版本；后台刷新或第三次修改后，旧选择会被拒绝。
- 删除使用永久 tombstone；删除与修改相遇是冲突，不把修改内容丢掉。
- 网络列表失败、目录失联或未返回完整列表不能解释为删除。
- 断线后查询同一个操作 UUID 的提交结果；不会用新 UUID 盲重放旧改动。
- 记忆恢复旧备份时，同步先暂停并清除旧基线/待网络提交状态，核对后才能重新启用。

同步不是历史备份，仍应保留 App 备份和独立服务器备份。同步根在普通工作区镜像之外，App 状态在 `noBackupFilesDir/memory-sync/`，现有 `ws-sync.sh` 无需取得它们；不要把这两处移动进工作区再 rsync。

## 实现与安全

Room 32→33 为每个路径增加单调 generation 和 tombstone。原模型工具的 12 位内容哈希接口保留；Repository/DAO 统一事务覆盖所有写入口，包括复制和批量删除。导入、模型写入和同步回写共用最终正文策略。无状态云中转、无新增公网 HTTP API、无常驻后台服务。

SSH 复用现有加密凭据和 host key 审核，只把 JSON 通过 stdin 传给固定 helper 命令。记忆文件和导出包不带 SSH 凭据；诊断默认不记录正文。库内容会离开手机，绑定前请确认目标账户权限和备份策略。

## 自动化验证

见 [TESTING.md](TESTING.md)。Linux 一条命令运行 Python 真进程测试、记忆 JVM 测试、真实 SSH 回归、debug/androidTest 构建和 APK 签名/包名核验。设备模式需要显式许可安装隔离测试包，不卸载或清数据。

实际执行结果与仍未执行的设备层级记录在 [VALIDATION.md](VALIDATION.md)，不能把 host 测试通过写成手机 UI 或后台验收通过。
