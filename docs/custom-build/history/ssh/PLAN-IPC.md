# P2：PRoot Unix socket短宿主路径验证与候选修复

## 已证实与未证实

用户已测：CLI/musl Node v24.22.1可运行；TCP_LOOPBACK成功，/tmp短名与VSCode长度Unix socket均EINVAL。传统bin/commit/node为glibc构建，PT_INTERP加载器缺失。真实PRoot翻译后的sockaddr长度尚未直接捕获，因此短路径映射是待真机验证的候选修复，不宣称因果已完全证明。

## 最小方案

1. 在ssh-p1隔离分支，从96b94e15留回退锚点。
2. 不绑定或改写全局/tmp，不动/workspace/Rootfs位置。给WorkspaceManager.executeCommand/startBackgroundCommand追加默认空的per-command extraBindMounts，仅SSH与IPC探针传入/vscode-tmp。
3. SSH选定工作区的IPC目录放Android私有cache短路径；限制宿主路径UTF8字节预算，独立短槽+私有owner标记避免不同工作区碰撞，权限700。目录内容不主动递归删除，避免跟随用户创建的symlink或删活跃socket；临时残留依赖应用cache生命周期。
4. 显式创建guest挂载点（PRoot不会建）；保持原/tmp里的PRoot PID/log追踪位置不变。sshd配置用SetEnv TMPDIR=/vscode-tmp，命令启动也export该变量。此挂载只跟随SSH服务及其子进程，普通Agent shell/终端不改。
5. UI展示IPC路径与检查入口。探针优先找已下载CLI/server/node，不自动安装VS Code Server或glbic。用同一per-command挂载对比/tmp与/vscode-tmp的socket监听+连接，再检查TCP，打印宿主路径预算信息；无Node则明确提示。
6. 真机通过后再让用户恢复remote.SSH.useExecServer=true，保留remoteServerListenOnSocket=false作为客户端意图；让CLI自然使用新的TMPDIR。先不切回传统glibc包、不改Node二进制。

## 测试/交付

- native Kotlin/JUnit测试短路径预算、slot隔离/碰撞/权限路径检查、额外挂载不影响普通调用、不允许覆盖/tmp或既有挂载。pwsh脚本为测试与构建唯一入口。
- 新APK保持.sshprobe/原签名/version3.1.2/code195；不覆盖已可用LAN ZIP，不动生产ops-v2或CT资源/网络。
- 主APK/androidTest、相关JVM、lint/格式分别记录；后台监视。
- 未得手机新目录Unix socket成功证据前，不宣称VSCode连接修复完成。
