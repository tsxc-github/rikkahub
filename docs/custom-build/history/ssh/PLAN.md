# RikkaHub SSH 开发桥接实施计划

依据：用户上传《实施规格 v1.0》（2026-11-05）。

## 约束
- P0 真实登录/命令/PTY/SFTP/转发/停止清理验证之前，不实现 P1 UI。
- 不修改正在使用的 ops-v2 checkout，不改宿主/CT 配置，不开公网/局域网监听，不操作防火墙。
- 当前手机工作区含用户真实数据：使用独立测试目录、临时测试密钥、独立 sshd 配置；不读取或覆盖既有 .ssh 私钥/authorized_keys。
- 测试 sshd 仅回环监听，测试结束/失败关闭；不常驻、不自动恢复。
- 不开发文件同步、不安装 VS Code Server。

## 阶段
1. 核对远端 feat/background-shell SHA、仓库 AGENTS.md 与实际工作区后台 API，保留源码锚点及与当前安装构建的差异。
2. P0：本手机现有 PRoot 检查环境。若缺少 sshd，仅为开发验证安装 Alpine openssh-server（国内镜像）；产品首版不自动安装依赖。
3. 编写可重复的独立测试入口与记录。先配置校验，再以 sshd -D -e 进行回环真实协议测试；验证主机密钥，绝不关闭校验。测试不替代电脑到手机/目标分支后台句柄的验收。
4. 若 P0 阻塞：保存最小复现、日志、测试矩阵，停止 P1；如 P0 全部门槛通过，进入独立 SshDevManager/Service/UI，并按仓库规范构建、单测、lint、格式检查。
   - 用户选择了「可以连接电脑 ADB；先准备 P0 测试构建与命令」。使用独立 `ssh-p0` worktree、独立 `.sshprobe` debug 包名，新增 androidTest，不覆盖现有 App。通过真实 WorkspaceManager 后台句柄验证活动连接清理；电脑测试仅走 adb forward 回环，不监听 LAN。
5. 交付每阶段已验证/失败/未验证清单、diff/脚本/产物与回滚。

## 已有环境核对
- coding checkout：ops-v2 @ a43419013fcf1b0cac1514cc8637ed98bd157d7d；仅 wrapper 配置有未提交改动，不触碰。
- 远端目标分支（ls-remote）：068dea4917e534cb3ed186ae963df98e66c084a4（还需核对本地 remote ref）。
- 本手机：aarch64 / Linux 6.6.118-android15；OpenSSH 客户端 10.3p1；未发现 sshd。
- Android 正式版本/API 尚未可见，不能以内核名替代。
