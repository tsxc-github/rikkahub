# P0 — ADB 真机与电脑验收

**状态：测试代码已准备，构建已启动；以下步骤尚未在新测试 APK 上执行。**
本文件不是 P1 正式使用指南。只测 SSH/SFTP 与现有后台句柄，绝不因测试通过就承诺 FGS/VS Code 兼容。

## 0. 安全边界

- 应用包名 `me.rerere.rikkahub.sshprobe`；测试包 `me.rerere.rikkahub.sshprobe.test`。
- 与官方/ops/ops-v2 应用隔离。**不要把正式数据或密钥拷进测试工作区。**
- sshd 只监听手机 127.0.0.1:2222，电脑仅经 USB ADB forward 访问；不需要改路由器/防火墙。
- 该版本只有 instrumentation 测试入口，不会自动开启 SSH，没有正式设置页/FGS。
- 电脑私钥永远留在电脑上；只向测试传入新建的测试公钥。
- 完整 Shell 能访问测试 App 自己的 Rootfs 和全部既有挂载，不是 /workspace chroot。
- 测试被系统异常杀死时，不能假定 finally 执行。执行 `adb shell am force-stop me.rerere.rikkahub.sshprobe`，确认端口拒绝，再清理/重测。

## 1. 产物与安装

构建成功后需要两个 APK（以实际 build/outputs 文件名为准）：

```
app/build/outputs/apk/github/debug/       # 主应用，优先 arm64-v8a 变体
app/build/outputs/apk/androidTest/github/debug/  # instrumentation APK
```

尚未产出时不要使用旧 APK 替代。后续会提供实际路径、SHA256 与 build/test/lint 退出码。

电脑执行（文件名替换为实际产物）：

```bash
adb devices -l
adb install -r PATH_TO_SSH_PROBE_ARM64_DEBUG.apk
adb install -r PATH_TO_SSH_PROBE_ANDROID_TEST.apk
adb shell pm list instrumentation
```

应看到 instrumentation 指向 `me.rerere.rikkahub.sshprobe`，runner 为 `androidx.test.runner.AndroidJUnitRunner`。
首次打开隔离测试 App，在它的工作区设置中新建一个 **本地** 工作区，安装 Linux Rootfs，等待 READY。
在这个测试工作区的终端安装测试依赖（软件源选国内镜像）：

- Alpine：`openssh-server openssh-client-default openssh-keygen git bash`；sftp 在 openssh-client-default 中。
- Debian/Ubuntu：`openssh-server openssh-client git bash`。
- 探针不会代你运行 apk/apt，不要为此开启系统 ssh/rc 服务。
- Debian/Ubuntu 如果 `sshd -t` 提示缺 `/run/sshd`，在这个隔离 Rootfs 中 `mkdir -p /run/sshd; chmod 755 /run/sshd` 后重试。

列出工作区 UUID（本地工作区 root=id，仓库创建逻辑已核对）：

```bash
adb shell run-as me.rerere.rikkahub.sshprobe ls files/workspaces
```

下文使用 Bash/WSL/macOS shell 变量。Windows PowerShell 也可直接把变量替换为实际值。

```bash
WS='这里填测试工作区UUID'
```

## 2. 自动 P0：真实 App 后台句柄

```bash
adb shell am instrument -w -r \
  -e class me.rerere.rikkahub.ssh.SshP0DeviceTest \
  -e workspaceId "$WS" \
  me.rerere.rikkahub.sshprobe.test/androidx.test.runner.AndroidJUnitRunner

adb exec-out run-as me.rerere.rikkahub.sshprobe \
  cat files/ssh-p0-report.txt > p0-auto-report.txt
```

预期（**需要实际执行核验**）：

1. 检查 LOCAL/READY 和依赖，配置校验通过。
2. 真正 `startBackgroundCommand()` 启动 sshd -D -e；读取 SSH banner。
3. 公钥命令、/skills 可见、强制 PTY、Git、SFTP put/rename/get、Android 直接读取同一文件、direct-tcpip 通过。
4. 保持活动 SSH 客户端；用真正句柄 kill()，waitFor 退出；活动客户端断开。
5. 手机 2222 可重新绑定，之前观察到的 PRoot tracee（含 PID+启动时间）不再存活。
6. 新普通工作区命令仍可运行；测试临时目录/密钥删除。

**只有 instrumentation `OK` 和报告对应 PASS 才算这一步通过。**
如系统限制读取 /proc、FileObserver 不可靠、PRoot 清理有残留，测试应失败并记录阻塞，不手动删测试断言来做绿。

## 3. 电脑模式：SSH / 人工交互 / SFTP / 转发

### 准备电脑测试公钥与 USB 转发

在电脑为本次测试建立新的独立密钥（不使用/上传现有私钥）：

```bash
ssh-keygen -t ed25519 -f ./p0-computer
# 提示时可给私钥设口令；密钥只留电脑。
PUB64=$(python3 -c "import base64,pathlib; print(base64.b64encode(pathlib.Path('p0-computer.pub').read_bytes()).decode())")
adb forward --no-rebind tcp:2222 tcp:2222
```

### 终端 A：显式启动探针

```bash
adb shell am instrument -w -r \
  -e class me.rerere.rikkahub.ssh.SshP0DeviceTest \
  -e workspaceId "$WS" \
  -e mode computer \
  -e publicKeyBase64 "$PUB64" \
  me.rerere.rikkahub.sshprobe.test/androidx.test.runner.AndroidJUnitRunner
```

该终端会保持等待，电脑测试窗口最多 15 分钟。探针仍会先做自动协议检查。
不要把 instrumentation 当常驻后台服务；测试期间保持 App/设备可用，不强制杀进程。

### 终端 B：查看指纹和工作目录

```bash
adb exec-out run-as me.rerere.rikkahub.sshprobe cat files/ssh-p0-report.txt
```

看到 `READY FOR COMPUTER` 后记录：

- `HOST FINGERPRINT`（本次临时 host key 指纹，每次重测都会变化）；
- `directory=/workspace/.ssh-p0-...`，下文记为 `DEMO`。

```bash
DEMO='/workspace/.ssh-p0-从报告复制完整目录'
```

### 核验 SSH 和真正交互式 PTY

```bash
ssh -i ./p0-computer -p 2222 \
  -o UserKnownHostsFile=./p0-known-hosts \
  root@127.0.0.1 'uname -m; pwd; ls -ld /workspace /skills'

ssh -i ./p0-computer -p 2222 \
  -o UserKnownHostsFile=./p0-known-hosts \
  root@127.0.0.1
```

首次连接必须人工比较 SSH 提示和报告里的 host fingerprint，再确认接受。
**不使用 StrictHostKeyChecking=no。** 如果重测产生新的 host key，只处理独立文件
`p0-known-hosts` 中这一项，不删除全局 known_hosts，不盲目绕过告警。

在交互会话中验证 `tty`、`bash`、`git -C <DEMO>/repo status`，再退出。
记录终端窗口缩放、Ctrl-C 等行为，不把强制 PTY 单条命令当成人工交互已通过。

### 电脑 SFTP 改同一文件

```bash
printf 'reviewed-on-pc\n' > ./computer.txt
sftp -i ./p0-computer -P 2222 \
  -o UserKnownHostsFile=./p0-known-hosts root@127.0.0.1
```

SFTP 交互命令（替换 DEMO 为报告给出的真实目录）：

```
put computer.txt /workspace/.ssh-p0-.../computer-upload.txt
rename /workspace/.ssh-p0-.../computer-upload.txt /workspace/.ssh-p0-.../computer.txt
get /workspace/.ssh-p0-.../computer.txt computer-downloaded.txt
bye
```

用手机测试工作区终端/Agent 读取这个 `computer.txt`，应立即为 `reviewed-on-pc`，没有 Git 同步。
停止前 instrumentation 也会用 Android File 直接核验同一内容（末尾有换行）。

### 电脑本地端口转发（独立于 direct-tcpip 自动检查）

可在手机测试工作区终端显式运行一个仅回环测试 HTTP 服务（如已安装 python3）：

```
python3 -m http.server 8765 --bind 127.0.0.1 --directory /workspace
```

然后电脑另一终端执行：

```bash
ssh -N -i ./p0-computer -p 2222 \
  -o UserKnownHostsFile=./p0-known-hosts \
  -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:18765:127.0.0.1:8765 root@127.0.0.1
# 另一个终端：curl http://127.0.0.1:18765/
```

记录结果并 Ctrl-C 结束两端测试服务。不要把 HTTP 服务开到 0.0.0.0。
没有 python3 时也可使用已有回环测试服务；不要为 P0 安装 VS Code Server。

### 主动停止，验证活动连接与新连接

可另保留一个交互式 SSH 会话观察断开，再从另一电脑终端发送 STOP：

```bash
ssh -i ./p0-computer -p 2222 \
  -o UserKnownHostsFile=./p0-known-hosts \
  root@127.0.0.1 "printf stop > '$DEMO/STOP'"
```

探针收到文件事件后，会持有自己的活动客户端，再调用 App 真正的后台句柄 kill()。
STOP 那条 SSH 连接可能在收到完整响应前被断开；以 instrumentation 报告和端口检查为准。

终端 A 应结束。再次连接应失败，不能再出现 SSH banner：

```bash
ssh -o BatchMode=yes -o ConnectTimeout=3 \
  -i ./p0-computer -p 2222 \
  -o UserKnownHostsFile=./p0-known-hosts root@127.0.0.1 true

adb exec-out run-as me.rerere.rikkahub.sshprobe \
  cat files/ssh-p0-report.txt > p0-computer-report.txt
adb forward --remove tcp:2222
```

## 4. 失败记录与回滚

保存自动/电脑模式 report、instrumentation stdout、Android/API、ABI、电脑 OS/OpenSSH 版本、
APK SHA256 和构建 commit。原始 logcat 不应整份公开，先检查敏感信息。
如果失败，不继续 P1；把失败阶段与可重复命令反馈。

紧急结束只针对隔离包：

```bash
adb shell am force-stop me.rerere.rikkahub.sshprobe
adb forward --remove tcp:2222
```

不再需要时卸载两个隔离包（会删除测试数据，不影响现有正式/ops App）：

```bash
adb uninstall me.rerere.rikkahub.sshprobe.test
adb uninstall me.rerere.rikkahub.sshprobe
```

P1、系统 FGS/休眠恢复和官方 VS Code Remote-SSH 均尚未由这些步骤验证。
