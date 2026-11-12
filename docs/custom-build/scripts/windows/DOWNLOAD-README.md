# RikkaHub SSH P0 Windows 测试包

基于3.1.2 + ops-v2 (`3adc2271`)，SSH探针提交 `7f784084`。这是隔离测试构建，不是正式SSH服务，不代表VS Code Remote-SSH可用。

## 文件

- `windows/Test-RikkaSsh.ps1`：PowerShell 5.1/7测试助手（尚待Windows实际执行）。
- `windows/WINDOWS-TESTING.md`：完整准备、自动测试、电脑SSH/SFTP、端口转发和停止步骤。
- `artifacts/ssh-p0-v3.1.2-arm64-debug.apk`：主应用，包名me.rerere.rikkahub.sshprobe，版本3.1.2/code195。
- `artifacts/ssh-p0-v3.1.2-androidTest.apk`：instrumentation，包名me.rerere.rikkahub.sshprobe.test，仅指向隔离主应用。
- `SHA256SUMS`：各文件校验值。
- `BUILD-STATUS.txt`：构建/测试状态与已知检查失败。

## 最短开始

确保Android platform-tools的adb在PATH中，手机已启用USB调试并授权电脑；安装Windows OpenSSH Client。
在本目录打开PowerShell：

```powershell
adb devices -l
powershell -NoProfile -ExecutionPolicy Bypass -File .\windows\Test-RikkaSsh.ps1 -Mode Install
```

首次打开隔离应用，创建**本地**工作区、安装Rootfs等READY，在测试工作区安装OpenSSH/git/bash依赖。不要导入正式数据。然后：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\windows\Test-RikkaSsh.ps1 -Mode Workspaces
powershell -NoProfile -ExecutionPolicy Bypass -File .\windows\Test-RikkaSsh.ps1 -Mode Auto -WorkspaceId '测试工作区UUID'
```

把`windows/reports/`里的报告发回。Auto通过之后再按WINDOWS-TESTING.md进入电脑模式。

## 边界

只经USB ADB转发到手机回环，不改防火墙、不打开LAN/公网，不上传电脑私钥、不安装VS Code Server。应用/测试APK构建和JVM测试通过，但lint/格式检查存在继承问题，不能称全部检查通过。Android真机生命周期与Windows连接尚未验收。
