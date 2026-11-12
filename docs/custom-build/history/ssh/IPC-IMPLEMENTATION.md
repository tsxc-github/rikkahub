# P2 短IPC路径候选修复实施

## 最新交付

- 最终HEAD **198983c032cfccd866a62889c46a37bf01e83b20**，19c8f6a5+198983c0两提交；补丁已取回`logs/ipc/ssh-ipc.mbox`，格式化后源码回取`source/`。
- assemble exit0（主APK/androidTest）；app618+workspace65=**683项JVM全部通过**，无错误/跳过。核心73项格式化后通过（0.248s），coding生产Node探针4项listen/connect通过。
- lint67 errors / 348 warnings / 1 hint均为继承，未出现新IPC/manager/settings项；spotless还是WorkspaceRepository.kt/WorkspaceReadChunk.kt继承两文件。总driver exit1，不能称全检查绿。
- `Verify-IpcPackage.ps1`核验两APK签名、.sshprobe/3.1.2-code195/arm64、instrumentation target、原service/权限、DEX内SshIpcStorage/SshIpcProbeKt/extraBindMounts/SetEnv TMPDIR=/vscode-tmp和ops query资源。
- 主APK SHA256 **4865d9b93e92e1590ca06730ac93d76bd016445752d1b53077259ad307b46fee**；测试APK **b155622a2a49a7bf00a30872532e7017b00d8da5a26dd0d95fc81b8f9d6d4959**。旧签名不变，-r升级保留数据。
- `Publish-IpcPackage.ps1`按白名单打包并核对复制前后hash/ZIP条目及解压读取。交付 **`/mnt/coding/rikkahub-build/downloads/ssh-ipc-v3.1.2-windows.zip`**，63,529,587字节，SHA256 **d92c70a959b0e0ed9a3536c0d9d9172635c7a64892d1949d4d89e96746269f5e**；元数据`logs/ipc/verification.json` / `logs/ipc/package.json`。内含两个APK、pwsh安装助手、v3诊断及说明，无私钥/Rootfs/local.properties。
- 新包待手机先点“检查VS Code IPC”；新目录ok/listening通过后再恢复CLI(true)并重连VSCode，不拿旧/tmp对照或coding测试当作手机成功证据。P2尚未通过。

以下保留实现与后台构建过程，PID21966/monitor67494e58均已结束。

## 证据

用户探针 `[设备原始日志未公开]`：CLI/musl Node可运行，TCP_LOOPBACK成功，/tmp短名与code-UUID名Unix socket均EINVAL。传统bin/commit Node已证实依赖缺失glibc loader。不再盲补库/换发行版；PRoot宿主路径翻译仍为候选根因，未声称sockaddr长度已直接证实。

## 已实现（尚未真机验证）

- `WorkspaceManager.executeCommand/startBackgroundCommand`追加默认空的`extraBindMounts`。不修改全局bindMounts/layout/普通terminal；逐次校验额外目标，禁止覆盖/tmp、/workspace、内核挂载及已有共享挂载；禁止重叠/dotdot/double-slash/control/symlink/不存在guest/source目录。
- SSH服务及专用探针才绑定 **/vscode-tmp**，不动全局/tmp，避免破坏现有PRoot PID/log跟踪。
- `SshIpcStorage`在Android私有cache创建短`v-xxxx`槽，owner SHA记录存files/ssh-dev/ipc-owners、不在guest bind内；不同工作区碰撞时选下个槽，不删除/占用未知缓存，权限700/600，拒绝symlink。宿主UTF8字节长度为52字节文件名预留空间，总sockaddr路径预算107字节；不承诺无限长扩展socket名。
- 显式创建空guest挂载点。原有同名非空目录不隐藏/覆盖；guest symlink拒绝。无递归清缓存，避免删除活跃socket或跟随symlink。
- sshd配置 **SetEnv TMPDIR=/vscode-tmp**，启动命令也export，仅影响该SSH会话树。host key、公钥、LAN模式、Rootfs与工作文件不迁移。
- UI新增“检查VS Code IPC（临时探针）”：使用已下载且可运行CLI Node，同一scope bind测试TCP、旧/tmp、新短目录短名、新目录VSCode长名，含真实客户端echo连接，不只是listen。输出HOST_PATH_BYTES和每项ok/listening；不安装Server/依赖。
- Node程序放`SshIpcProbe.kt`纯生成函数，PWSH集成测试通过JVM emitter拿实际生产程序执行，不维护测试镜像。

## 已验证

- `Build-Ipc.ps1 -Stage Core`：真实Kotlin **73项通过（0.251s）**；包含原53+IPC路径/权限/隔离/碰撞/生成程序策略20。
- 同一生产Node探针在coding普通Linux上 **4项listen+connect通过**（Node20.19.2）。这不是手机PRoot证明。
- workspace新增9项CommandBindMountTest，覆盖extra scope不泄漏普通命令/terminal及路径安全；首轮完整构建的assemble/unit阶段已exit0，但新一轮最终XML计数尚未归档。
- Windows诊断脚本升级v3，显示SSH_SESSION_TMPDIR/NODE_TMPDIR，新增UNIX_SESSION_TMP；保持显式LF stdin/exit code。实际pwsh传输/脚本解析/Node语法 **5项通过**。旧/tmp探针在新包中仍可能失败，这符合“不改全局/tmp”的设计。

## 当前后台任务

- 当前PID **21966**，入口 `/mnt/coding/rikkahub-build/tools/pwsh-7.6.9/pwsh -NoProfile -File Build-Ipc.ps1 -Stage Full`。
- 日志 `/mnt/coding/rikkahub-build/logs/ssh-ipc/driver.log`，最终exit-code/SSH_IPC_BUILD_EXIT，各阶段分别记录。
- monitor **67494e58**，Watch-IpcBuild.ps1，每30秒、结束即退。
- 初轮logs/ssh-ipc-initial保留；其assemble/unit已通过。审阅把局部变量protected改名protectedTargets（Kotlin软关键字并未造成实际编译失败），为保持源码/产物一致重新构建；仅对本任务进程组发TERM，未动其他服务/Gradle daemon/CT配置。
- 新IPC APK还没有最终核验/上传，coding现有LAN ZIP没有短IPC映射。回退archive/ssh-p1-before-ipc-96b94e15。

## 下一步

构建完成核验新APK，再交付独立IPC ZIP。用户先运行App内探针；只有新目录Unix socket listen+connect通过后，再停/开SSH让SetEnv生效，恢复Windows remote.SSH.useExecServer=true（传统glibc包不能用），并验证VSCode远程窗口。不要再删全Server目录，不以coding测试或/tmp旧探针代替手机新目录证据。P2尚未通过。
