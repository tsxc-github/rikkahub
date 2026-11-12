# IPC候选修复真机验收（新包核验后）

IPC版HEAD198983c0的APK已完成构建、签名/代码静态核验，683项JVM测试通过。请使用新的ssh-ipc-v3.1.2-windows.zip；已有LAN包没有/vscode-tmp短映射。手机IPC与VSCode窗口仍待本轮验收。

1. 新包只-r升级.sshprobe，不卸载、不改已有工作区/库/Server下载。打开SSH设置，选原READY工作区。
2. 暂不启动VSCode，点击“检查VS Code IPC（临时探针）”，复制完整输出。
3. 检查HOST_PATH_BYTES、TCP_LOOPBACK、UNIX_DEFAULT_TMP、UNIX_SHORT_BIND、UNIX_VSCODE_BIND。旧/tmp失败仍属预期对照；新目录两项必须**ok:true且listening:true**，表示监听与真实连接echo均成功。
4. 若新目录也失败就停在探针阶段，不循环重连VSCode、不切换发行版；发实际错误。若提示目录过长/无可用CLI Node/guest目录非空，按具体证据处理，不删除用户数据。
5. 新目录通过后，手机停止再保存启动SSH，使新的mount与sshd SetEnv生效。复测普通SSH/SFTP/LAN、通知停止无回归。
6. Windows本地VSCode用户settings.json恢复：

```json
"remote.SSH.useExecServer": true,
"remote.SSH.remoteServerListenOnSocket": false
```

传统useExecServer=false已证实会拿glibc Node，不再使用。只合并这两项，不覆盖其他设置。

7. 停止旧的该手机VSCode Server进程/远程连接后重连。不删.vscode-server及extensions/workspace；现有CLI musl Node与已补库保留。
8. 如需检查实际SSH会话TMPDIR，coding独立诊断Get-VscodeProbe.ps1已为v3。应显示SSH_SESSION_TMPDIR与NODE_TMPDIR为/vscode-tmp、UNIX_SESSION_TMP成功；旧/tmp的两项仍可能失败，不要据此误判新映射失败。
9. VSCode打开/workspace，编辑文件后手机立即读取，验证终端、Git Diff和至少一个实际扩展；只有进入窗口不等于全部扩展/语言服务器可用。

最终记录：APK SHA/构建commit、App探针输出、客户端VSCode/RemoteSSH版本、实际TMPDIR、远程窗口和编辑/终端结果。不要上传连接token/私钥/整份未脱敏logcat。
