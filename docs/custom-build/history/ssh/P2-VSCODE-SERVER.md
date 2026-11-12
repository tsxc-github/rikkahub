# P2 VS Code Remote-SSH：当前诊断

## 当前行动：IPC候选APK已出包

198983c0已构建并核验，683项JVM通过（含核心73项），coding Node探针4项通过；lint/格式继承问题仍在，详见IPC-IMPLEMENTATION.md。coding下载`/mnt/coding/rikkahub-build/downloads/ssh-ipc-v3.1.2-windows.zip`。用户先升级并测App内UNIX_SHORT_BIND/UNIX_VSCODE_BIND，成功后再恢复useExecServer=true使用musl CLI。没有手机新挂载成功或VSCode窗口通过证据前，不宣布P2通过。

## 最新：Unix socket对照完成，进入App候选修复

用户 `[设备原始日志未公开]` 已给出完整结果：TCP_LOOPBACK=true；UNIX_SHORT与UNIX_VSCODE_LENGTH均EINVAL。缺库不是当前阻塞，CLI/musl Node可运行，传统glibc Node不能运行。不能把结果扩大为整个系统所有Unix socket均不支持，宿主路径映射/长度仍待新路径对照验证。

正在构建仅SSH的短IPC bind+SetEnv候选修复，详见 `IPC-IMPLEMENTATION.md`；73项Kotlin+4项coding Node实际listen/connect通过，但手机新目录探针尚未运行。Windows诊断入口已为v3，5项pwsh传输/语法检查通过；旧/tmp对照不应误算为新映射结果。当前不继续让用户盲改开关，等新包先测新目录，P2仍未通过。

以下为逐轮证据/历史尝试。

## 最新实证：传统包是glibc，CLI包是musl；原诊断脚本here-doc失败

用户报告 `[设备原始日志未公开]`：Alpine3.24.1/arm64。传统bin/commit/node文件存在、可执行权限，但ELF PT_INTERP为/lib/ld-linux-aarch64.so.1且loaderExists=false，执行127；CLI/server/node的PT_INTERP为/lib/ld-musl-aarch64.so.1且loaderExists=true，版本v24.22.1、执行0。故glibc/musl分发差异已证实，不再将not found误判为下载缺文件，也不再建议盲加glibc或换发行版。

原诊断脚本末尾JS here-doc终止标记被当成JavaScript，报ReferenceError，socket探测未得到结果；这是脚本传输兼容问题，非用户环境的新Node故障。版本2改用ProcessStartInfo明确UTF8无BOM+LF+末尾LF写stdin，显式捕获Process.ExitCode/stdout/stderr，避免Windows PowerShell管道追加CRLF；ssh可执行文件只选Get-Command的首个结果，避免PATH重复项拼接。

新版仍在coding同一路径`/mnt/coding/rikkahub-build/downloads/Get-VscodeProbe.ps1`，输出PROBE_SCRIPT_VERSION=2。真实pwsh测试`Test-VscodeProbeTransport.ps1`提取实际helper函数执行，4项通过：完整脚本解析、CRLF/无末尾换行shell here-doc、Node here-doc、退出23/stdout/stderr精确捕获。该测试运行于coding Linux，不冒称Windows实测。

当前需要用户覆盖旧脚本、再跑一次，取得TCP_LOOPBACK/UNIX_SHORT/UNIX_VSCODE_LENGTH三项结果。后续应优先使用已可运行的CLI/musl构建处理IPC，不回退传统glibc构建。Unix socket真实失败类型仍未被本次脚本验证，不宣称路径超限已证实或P2通过。

以下为之前证据和尝试的历史。

## 最新：传统链路切换后，另一份Node执行ENOENT

用户提交 `[设备原始日志未公开]`：Remote-SSH0.128.0切到旧链路后执行 `/root/.vscode-server/bin/261c84cb.../node` 报not found、exit200。与之前已通过完整性/运行到socket监听的 `cli/servers/Stable-.../server/node` **不是同一安装目录/二进制**。

可能是传统包Node物理缺失，也可能是文件存在但ELF PT_INTERP加载器缺失（例如glibc构建落入musl环境）；不能仅凭shell not found断言是哪一种。上轮TCP绕过未成功，不再盲目切设置/删缓存/安装glibc。

已准备只读pwsh诊断入口 `windows/Get-VscodeProbe.ps1`，coding下载 `/mnt/coding/rikkahub-build/downloads/Get-VscodeProbe.ps1`。在用户Windows原测试包目录执行，复用本地私钥/专用known_hosts，不上传私钥。检查两份Node的exists/version/ldd；如找到可执行Node，读取两份ELF PT_INTERP并检查loader存在，再测试短Unix socket、VSCode长度Unix socket与TCP loopback。只创建并关闭自己的随机临时socket，不改依赖或Server文件。PowerShell解析已通过，尚未由用户实际执行。

等待诊断输出后再选：若CLI Node可用且TCP可用而Unix失败，回到CLI构建精确修IPC路径/PRoot问题；若物理文件缺失则按实际安装残留诊断。P2仍未通过。

以下为前两轮证据与尝试历史。

## 最新：缺库已解决，阻塞转到Unix socket监听

用户补依赖后提交 `[设备原始日志未公开]`：VSCode Server1.144.1/arm64、commit261c84cb...完整性自检通过、setup complete、node已运行并启动extension host agent。当前真正失败行为 `listen EINVAL ... /tmp/code-2667841a-d411-464c-a803-5c03c8a41cfd`，是文件路径Unix socket监听，不是SSH TCP端口冲突或下载/缺库。MAC address异常后程序继续执行，暂非首个处理目标。

PRoot真实路径翻译/Unix socket地址长度是候选根因，但尚未用该设备实际sockaddr/真实路径验证，不能断言长度超限已证实。先采用最小可逆绕过：Windows本地VSCode用户设置显式 `remote.SSH.useExecServer=false`、`remote.SSH.remoteServerListenOnSocket=false`，停止该手机旧Server后重连，尝试经典TCP+SSH隧道启动链路。新CLI即使socket设置false仍可能内部用Unix socket，故同时关闭execServer。参考官方socket设置说明 https://code.visualstudio.com/docs/remote/troubleshooting 及实际新旧传输参数对照issue https://github.com/microsoft/vscode-remote-release/issues/11839 。

不删整个.vscode-server、不改正式Rootfs或塞glibc、不擅自重构App/tmp挂载。若仍在Unix socket失败，再收新日志确认是主监听还是其他内部IPC，做精确复现。当前未有成功进入VSCode远程窗口的证据，不宣称P2通过。

以下为第一次缺库诊断（历史，已经用户补依赖并由新日志证实解决）。

用户在LAN版SSH可用后尝试官方VS Code Remote-SSH，反馈反复下载Server。

## 日志证据

上传 `[设备原始日志未公开]`：server inflate 4324/4324（100%）完成；安装器执行staging中的server/bin/code-server --version作完整性自检，退出127。node明确缺libstdc++.so.6和libgcc_s.so.1，随后大量C++符号Error relocating。Server commit为261c84cb1eddf11235f9e2c921425e9663fbe556。

## 当前结论与最小下一步

这轮可定位的直接阻塞是缺少Alpine运行库libstdc++和libgcc，不是下载不完整。大量relocation错误很可能是缺这两个库的级联，不能据此直接判定必须换glibc发行版。

建议只在用户实际SSH连接的.sshprobe测试Rootfs中，用对应Alpine分支的国内apk镜像安装libstdc++与libgcc，再重连。不要在coding或正式RikkaHub的另一个Rootfs里装错位置，不删整个.vscode-server、不先塞glibc兼容层、不迁移工作区。

先前按微软官方文档将Alpine/musl列为兼容风险仍成立，但此日志已有更具体的缺库证据，修复后仍需验证Server是否成功启动及扩展行为。当前没有补库后通过的证据，不声称P2已通过。
