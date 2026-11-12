# Windows：SSH P0 验收

> 当前为 **3.1.2 + ops-v2 隔离测试包的准备说明**。新构建/哈希核验完成后再安装；本文件与脚本本身不代表测试成功。仅支持ADB USB回环连接，不开Wi-Fi/公网，不安装VS Code Server。

## 1. 准备

- Windows安装Android platform-tools，让`adb`在PATH内；启用手机USB调试并授权电脑。运行`adb devices -l`，应为device而非unauthorized。
- Windows安装OpenSSH Client，确认`ssh`、`sftp`、`ssh-keygen`可用。
- 使用Windows PowerShell 5.1或PowerShell 7；脚本不修改系统ExecutionPolicy。若公司/学校策略禁止脚本，遵循本机策略。
- 保持下载目录结构：`windows/Test-RikkaSsh.ps1`；`artifacts/ssh-p0-v3.1.2-arm64-debug.apk`和`artifacts/ssh-p0-v3.1.2-androidTest.apk`。也可以用`-ArtifactDirectory`指定APK目录。
- 多设备时所有脚本命令加`-Serial 设备序列号`。

在`windows`目录打开PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Test-RikkaSsh.ps1 -Mode Install
```

测试包`me.rerere.rikkahub.sshprobe`，不会覆盖正式/ops App。不要导入真实数据、工作区或私钥。

首次打开隔离App，在工作区设置创建**本地**工作区，安装Rootfs并等READY。在该工作区终端安装依赖，国内镜像：

```sh
# Alpine：先确认/etc/apk/repositories已使用清华等国内镜像
apk add openssh-server openssh-client-default openssh-keygen git bash
# Debian/Ubuntu：先确认apt源为国内镜像
apt-get update && apt-get install -y openssh-server openssh-client git bash
mkdir -p /run/sshd && chmod 755 /run/sshd
```

不要启动系统ssh/rc服务；正式探针会自己以前台方式启动并停止sshd。

## 2. 自动验证真实App后台句柄

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Test-RikkaSsh.ps1 -Mode Workspaces
$WS = '上一步得到的测试工作区UUID'
powershell -NoProfile -ExecutionPolicy Bypass -File .\Test-RikkaSsh.ps1 -Mode Auto -WorkspaceId $WS
```

脚本同时检查instrumentation的`OK`和探针报告`P0 AUTOMATED CHECKS PASSED`，报告含真实句柄kill、活动连接断开、端口释放和进程清理结果。ADB退出码0本身不等于成功。报告保存在`windows/reports/`。

失败就停止并发回两个报告文件，不删断言来做绿；脚本finally会force-stop**隔离测试包**，不会操作正式App。

## 3. 电脑真实SSH/SFTP测试

先为本次测试新建独立密钥（不要覆盖旧密钥；提示口令时自行选择，可设置口令）：

```powershell
ssh-keygen -t ed25519 -f .\p0-computer
```

私钥留在电脑。脚本只接受`.pub`中的单行Ed25519公钥。

终端A（保持运行，最多15分钟）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Test-RikkaSsh.ps1 -Mode Computer -WorkspaceId $WS -PublicKeyPath .\p0-computer.pub
```

终端B（同一windows目录）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Test-RikkaSsh.ps1 -Mode Report
```

若还没有`READY FOR COMPUTER`，等探针自动检查完成后再**手动**运行一次Report。记录`HOST FINGERPRINT`和`directory=/workspace/.ssh-p0-...`。

```powershell
$DEMO = '/workspace/.ssh-p0-从报告复制完整目录'
ssh -i .\p0-computer -p 2222 -o UserKnownHostsFile=./p0-known-hosts root@127.0.0.1
```

首次连接人工核对报告里的host key指纹后才接受；不使用StrictHostKeyChecking=no。重测的临时host key会变化，只处理本测试专用`p0-known-hosts`，不要删全局known_hosts。

在交互式SSH里检查`pwd`、`tty`、`ls -ld /workspace /skills`、`git -C <DEMO>/repo status`，尝试Ctrl-C、窗口缩放，再退出。

SFTP写入精确字节（UTF-8无BOM、LF末尾换行；不使用可能写UTF-16的PowerShell重定向）：

```powershell
[IO.File]::WriteAllText((Join-Path (Get-Location).Path 'computer.txt'), "reviewed-on-pc`n", (New-Object Text.UTF8Encoding($false)))
sftp -i .\p0-computer -P 2222 -o UserKnownHostsFile=./p0-known-hosts root@127.0.0.1
```

SFTP交互中替换真实DEMO目录：

```
put computer.txt /workspace/.ssh-p0-.../computer-upload.txt
rename /workspace/.ssh-p0-.../computer-upload.txt /workspace/.ssh-p0-.../computer.txt
get /workspace/.ssh-p0-.../computer.txt computer-downloaded.txt
bye
```

用手机**测试工作区**终端读取此`computer.txt`，应立即显示`reviewed-on-pc`，无需同步。

## 4. 电脑端口转发

如测试Rootfs已有python3，手机测试工作区终端运行：

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory /workspace
```

电脑另开终端：

```powershell
ssh -N -i .\p0-computer -p 2222 -o UserKnownHostsFile=./p0-known-hosts -o ExitOnForwardFailure=yes -L 127.0.0.1:18765:127.0.0.1:8765 root@127.0.0.1
# 另一终端（使用curl.exe而非PowerShell旧版curl别名）：
curl.exe http://127.0.0.1:18765/
```

记录结果后Ctrl-C结束两端HTTP/转发，不开0.0.0.0。无python3时可用已有回环服务，不为此安装VS Code Server。

## 5. 主动停止并确认断开

可保留一个交互式SSH连接观察断开，另一个终端发送：

```powershell
ssh -i .\p0-computer -p 2222 -o UserKnownHostsFile=./p0-known-hosts root@127.0.0.1 "printf stop > '$DEMO/STOP'"
```

探针会先确认电脑上传文件的精确内容，再调用真实后台句柄kill。STOP连接可能来不及收到正常退出码；以终端A最终报告为准。终端A结束后脚本删除ADB转发，并force-stop隔离包。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Test-RikkaSsh.ps1 -Mode Report
ssh -o BatchMode=yes -o ConnectTimeout=3 -i .\p0-computer -p 2222 -o UserKnownHostsFile=./p0-known-hosts root@127.0.0.1 true
```

新连接应失败。发送自动/电脑模式report和instrumentation输出，并说明Windows/OpenSSH版本、手机Android版本。端口经ADB转发断开不能单独证明Rootfs进程清理，必须同时看探针的kill/tracee/重新绑定检查。

紧急清理：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Test-RikkaSsh.ps1 -Mode Cleanup
```

**这不是正式SSH服务或VS Code Remote-SSH验收。** P0通过后再做按需服务/通知/UI；官方VS Code Server兼容性另测。
