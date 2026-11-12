# VS Code Unix socket：短IPC映射候选修复测试包

HEAD198983c0，隔离.sshprobe，3.1.2/code195，原debug签名。保留同Wi-Fi/热点与普通SSH功能；没有打包/安装VS Code Server，不换Rootfs、不改glibc。

## 先看状态

主APK+androidTest构建与静态核验通过，JVM **683/683通过**（app618+workspace65），核心73项另跑通过；同一生产Node程序在coding普通Linux上4项监听/连接通过。Windows诊断脚本v3通过5项pwsh传输/语法检查。

lint/格式仍有继承问题，见BUILD-STATUS.txt；总driver exit1，不是全检查绿。**手机新的Unix socket映射尚未真机验证，不能直接称VSCode已修好。**

## 1. 升级，不卸载

Windows PowerShell7，在解压目录运行（ADB只用于安装）：

```powershell
pwsh -NoProfile -File .\windows\Test-RikkaSshIpc.ps1 -Mode Install
```

这是-r升级，保留测试Rootfs、工作区、公钥、host key、已安装libgcc/libstdc++及下载的Server。只碰.sshprobe与其测试包，不覆盖正式RikkaHub。

## 2. 先在手机测IPC，不急着重连VSCode

手机设置→SSH开发连接，选原READY工作区，点 **“检查 VS Code IPC（临时探针）”**。

请复制完整输出，重点是：

```text
HOST_PATH_BYTES=...
TCP_LOOPBACK ... ok:true
UNIX_DEFAULT_TMP ... （仍可能EINVAL，是旧路径对照）
UNIX_SHORT_BIND ... ok:true, listening:true
UNIX_VSCODE_BIND ... ok:true, listening:true
```

探针会在App私有短cache目录创建自己的随机临时socket并做真实连接echo，不安装依赖、不动工作文件。新目录两项若失败，就停在这里，把错误发回，不循环重连VSCode。

## 3. 新目录成功后再连接VSCode

手机先停止再保存启动SSH，让新增的per-command挂载与sshd SetEnv生效。新SSH会话TMPDIR应为 **/vscode-tmp**。

Windows **本地用户**settings.json合并：

```json
"remote.SSH.useExecServer": true,
"remote.SSH.remoteServerListenOnSocket": false
```

传统useExecServer=false已经证实会拿glibc Node，不再使用。仅合并两项，不覆盖其他设置。停止该手机的旧VSCode Server/远程连接后重连；不删整个.vscode-server、extensions或工作区。

## 4. 必要时检查实际SSH会话环境

包内Get-VscodeProbe.ps1为v3，能显示SSH_SESSION_TMPDIR/NODE_TMPDIR与UNIX_SESSION_TMP。若要运行，复用之前电脑的专用私钥/known_hosts（不要上传私钥）：

```powershell
pwsh -NoProfile -File .\windows\Get-VscodeProbe.ps1 -HostName 手机IP -IdentityFile .\p0-computer
```

若原key或known_hosts在另一目录，传对应路径。该自动诊断使用BatchMode；加密私钥可使用SSH agent，不能为此关闭主机校验。传统glibc Node的not found以及旧/tmp的EINVAL在此包里仍可能出现，不是新短目录失败；关注CLI Node、TMPDIR和UNIX_SESSION_TMP。

## 5. 回归与边界

- 全局/tmp/PID日志与/workspace未移动；只是本SSH服务/probe树多一个/vscode-tmp绑定。
- 普通SSH/SFTP、Wi-Fi/热点、通知停止、网络地址消失应保持原行为。
- 短目录有107字节Unix路径预算，仅为已知文件名保留空间，不保证任意长扩展socket名称。
- 旧LAN/P1/P0 ZIP保留。若新包启动失败，先保留错误日志，不卸载或清数据；可用原LAN包-r回退。
- 进入VSCode窗口后，还需测打开/workspace、编辑保存、Git Diff、终端与实际扩展；P2不能只靠安装自检或coding测试通过判定成功。
