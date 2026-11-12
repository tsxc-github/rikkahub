# RikkaHub SSH P1 首版测试包

基于3.1.2 + ops-v2，源码HEAD `fe1a683c`。新增设置页与按需SSH前台服务；无需运行P0 instrumentation来开启服务。

**这仍是待真机验收的隔离测试版，不是VS Code Remote-SSH兼容性结论。** 主APK和测试APK已构建、签名/包名/版本/资源核验；JVM 632/632通过，其中31项为新增核心状态机/公钥测试。lint/格式检查未全绿：67个继承UI错误及2个继承ops文件格式问题，见BUILD-STATUS.txt。

## 1. 升级（不卸载、不丢之前测试Rootfs）

Windows安装PowerShell7，使用`pwsh`；确保`adb`、Windows OpenSSH Client的`ssh`、`ssh-keyscan`、`ssh-keygen`、`sftp`在PATH中。

在解压目录打开终端：

```powershell
pwsh -NoProfile -File .\windows\Test-RikkaSshP1.ps1 -Mode Install
```

脚本先核验两APK SHA256，再用adb升级`.sshprobe`与测试APK，不碰正式RikkaHub。签名与P0相同；不要先卸载，不要导入正式工作区数据。

## 2. 手机正式设置入口（不运行P0 Computer探针）

1. 打开隔离App，设置→**SSH开发连接**。
2. 选择P0使用过的本地READY工作区。
3. 添加电脑`p0-computer.pub`中的公钥，端口2222；私钥留在电脑。
4. 允许通知，点击“保存并启动”，等待RUNNING，查看主机指纹。

此版仅127.0.0.1，无LAN开关；不修改系统authorized_keys，不自动安装Rootfs软件或打开系统ssh服务。若缺依赖，按UI提示在测试Rootfs中手动安装，使用国内软件源。P1的host key是新的持久密钥，**不要把P0临时指纹当作P1指纹**。

## 3. Windows连接（PowerShell7）

保留原来的`p0-computer`私钥及`.pub`在电脑，例如放到本解压目录。不要上传私钥。

```powershell
pwsh -NoProfile -File .\windows\Test-RikkaSshP1.ps1 -Mode Forward
pwsh -NoProfile -File .\windows\Test-RikkaSshP1.ps1 -Mode PinHost -ExpectedFingerprint 'SHA256:从手机UI复制当前指纹'
pwsh -NoProfile -File .\windows\Test-RikkaSshP1.ps1 -Mode Connect -IdentityFile .\p0-computer
```

PinHost只在从网络得到的Ed25519 key指纹与**手机UI**精确相同后写独立known_hosts；不会关闭主机校验。已存在ADB转发时Forward会拒绝覆盖，先确认原规则属于自己的P0测试，再手动`adb forward --remove tcp:2222`后重建。

进入SSH后检查`pwd`、`tty`、`ls -ld /workspace /skills`、`git status`；尝试Ctrl-C、窗口缩放。SFTP可使用脚本创建的专用host文件：

```powershell
sftp -i .\p0-computer -P 2222 -o StrictHostKeyChecking=yes -o UserKnownHostsFile=./.rikka-ssh-p1-known-hosts root@127.0.0.1
```

上传/重命名/下载一个测试文件，并让手机测试工作区终端立即读取。再按P1-TESTING.md测真实-L转发、退后台/锁屏。

## 4. 停止与重启

保持一个SSH连接，在手机点设置页“停止”或通知“停止”，应断开已有连接、移除通知、状态STOPPED。ADB转发可保持不删，以排除“仅删了转发”的假停止。

```powershell
pwsh -NoProfile -File .\windows\Test-RikkaSshP1.ps1 -Mode CheckStopped -IdentityFile .\p0-computer
```

此脚本仅检查SSH命令不能成功；认证/ADB失败也会导致相同现象，必须结合UI、通知和原连接断开一起判断。

再次启动，同一工作区host fingerprint应保持一致。清除最近任务当前会主动停止SSH；普通Home退后台不是清除任务。强制停止后重新打开不得自动监听。异常遗留进程会fail-closed提示人工强制停止隔离App，不会盲杀复用PID。

紧急清理：

```powershell
pwsh -NoProfile -File .\windows\Test-RikkaSshP1.ps1 -Mode Cleanup
```

## 需要回报

先给：设置页是否可见、启动是否RUNNING、指纹是否稳定、Windows SSH/SFTP是否正常、通知停止是否生效。有错误时复制UI日志尾部与事件顺序；不要上传私钥或整份未脱敏logcat。

Windows `.ps1` 脚本尚未在Windows实际运行验证；Android P1服务/通知/权限/休眠行为也待此轮实测，不把P0通过当成P1通过。
